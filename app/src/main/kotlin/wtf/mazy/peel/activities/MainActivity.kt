package wtf.mazy.peel.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ImportMode
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.dialog.showInputDialog
import wtf.mazy.peel.ui.webapplist.GroupPagerAdapter
import wtf.mazy.peel.ui.webapplist.WebAppListFragment
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var pagerAdapter: GroupPagerAdapter? = null
    private var lastGroupKeys: List<Pair<String, String>> = emptyList()
    private var lastShowUngrouped: Boolean = true

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let {
                DataManager.instance.saveDefaultSettings()
                if (!BackupManager.exportToZip(it)) {
                    NotificationUtils.showInfoSnackBar(
                        this,
                        getString(R.string.export_failed),
                        Snackbar.LENGTH_LONG,
                    )
                } else {
                    NotificationUtils.showInfoSnackBar(
                        this,
                        getString(R.string.export_success),
                        Snackbar.LENGTH_SHORT,
                    )
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { buildImportModeDialog(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DataManager.instance.loadAppData()

        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)

        val fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener { buildAddWebsiteDialog() }
        personalizeToolbar()

        setupViewPager()
    }

    override fun onResume() {
        super.onResume()
        DataManager.instance.loadAppData()
        refreshCurrentPages()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Const.INTENT_BACKUP_RESTORED, false)) {
            setupViewPager()
            buildImportSuccessDialog()
            intent.putExtra(Const.INTENT_BACKUP_RESTORED, false)
            intent.putExtra(Const.INTENT_REFRESH_NEW_THEME, false)
        }
        if (intent.getBooleanExtra(Const.INTENT_WEBAPP_CHANGED, false)) {
            refreshCurrentPages()
            intent.putExtra(Const.INTENT_WEBAPP_CHANGED, false)
        }
    }

    private fun setupViewPager() {
        val groups = DataManager.instance.sortedGroups

        if (groups.isEmpty()) {
            tabLayout?.visibility = View.GONE
            pagerAdapter = GroupPagerAdapter(this, emptyList(), showUngrouped = true)
            viewPager?.adapter = pagerAdapter
            viewPager?.isUserInputEnabled = false
            lastGroupKeys = emptyList()
            lastShowUngrouped = true
        } else {
            val hasUngrouped = DataManager.instance.activeWebsitesForGroup(null).isNotEmpty()
            tabLayout?.visibility = View.VISIBLE
            pagerAdapter = GroupPagerAdapter(this, groups, showUngrouped = hasUngrouped)
            viewPager?.adapter = pagerAdapter
            viewPager?.isUserInputEnabled = true
            lastGroupKeys = groups.map { it.uuid to it.title }
            lastShowUngrouped = hasUngrouped

            TabLayoutMediator(tabLayout!!, viewPager!!) { tab, position ->
                tab.text = pagerAdapter?.getPageTitle(position)
            }.attach()
        }
    }

    fun refreshCurrentPages() {
        val groups = DataManager.instance.sortedGroups
        val newGroupKeys = groups.map { it.uuid to it.title }
        val newShowUngrouped =
            groups.isNotEmpty() && DataManager.instance.activeWebsitesForGroup(null).isNotEmpty()

        if (lastGroupKeys != newGroupKeys || lastShowUngrouped != newShowUngrouped) {
            setupViewPager()
            return
        }

        for (i in 0 until (pagerAdapter?.itemCount ?: 0)) {
            val fragment = supportFragmentManager.findFragmentByTag("f$i")
            (fragment as? WebAppListFragment)?.updateWebAppList()
        }
    }

    private fun personalizeToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.app_name)
        setSupportActionBar(toolbar)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_groups -> {
                startActivity(Intent(this, GroupListActivity::class.java))
                true
            }

            R.id.action_global_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.action_import -> {
                try {
                    importLauncher.launch("*/*")
                } catch (e: ActivityNotFoundException) {
                    NotificationUtils.showInfoSnackBar(
                        this,
                        getString(R.string.no_filemanager),
                        Snackbar.LENGTH_LONG,
                    )
                    Log.e("MainActivity", "No file manager available for import", e)
                }
                true
            }

            R.id.action_export -> {
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val currentDateTime = sdf.format(Date())
                try {
                    exportLauncher.launch("Peel_$currentDateTime.zip")
                } catch (e: ActivityNotFoundException) {
                    NotificationUtils.showInfoSnackBar(
                        this,
                        getString(R.string.no_filemanager),
                        Snackbar.LENGTH_LONG,
                    )
                    Log.e("MainActivity", "No file manager available for export", e)
                }
                true
            }

            R.id.action_about -> {
                buildAboutDialog()
                true
            }

            R.id.action_clear_data -> {
                showClearDataConfirmDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showClearDataConfirmDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_clear_data, null)
        val switchBrowsing =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchClearBrowsingData
            )
        val switchSandbox =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchClearSandboxData
            )
        val switchFactory =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchFactoryReset
            )

        switchBrowsing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && switchFactory.isChecked) switchFactory.isChecked = false
            switchSandbox.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) switchSandbox.isChecked = false
        }

        switchFactory.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && switchBrowsing.isChecked) switchBrowsing.isChecked = false
        }

        MaterialAlertDialogBuilder(this).setTitle(R.string.clear_data).setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (switchFactory.isChecked) {
                    performFactoryReset()
                } else if (switchBrowsing.isChecked) {
                    clearBrowsingData(switchSandbox.isChecked)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun clearBrowsingData(includeSandbox: Boolean) {
        SandboxManager.clearNonSandboxData(this)
        if (includeSandbox) {
            SandboxManager.clearAllSandboxData(this)
        }
    }

    private fun performFactoryReset() {
        SandboxManager.clearNonSandboxData(this)
        SandboxManager.clearAllSandboxData(this)

        DataManager.instance.getWebsites().toList().forEach { webapp ->
            webapp.cleanupWebAppData(this)
            DataManager.instance.removeWebApp(webapp)
        }
        DataManager.instance.getGroups().toList().forEach { group ->
            if (group.isUseContainer) {
                SandboxManager.wipeSandboxStorage(group.uuid)
            }
            DataManager.instance.removeGroup(group, ungroupApps = false)
        }

        DataManager.instance.defaultSettings = DataManager.instance.defaultSettings.also {
            it.settings = wtf.mazy.peel.model.WebAppSettings.createWithDefaults()
        }

        setupViewPager()
    }

    private fun buildAboutDialog() {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.gnu_license)).setPositiveButton(R.string.ok, null).show()
    }

    private fun buildImportModeDialog(uri: android.net.Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_import_mode, null)
        val switchMerge =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchMergeMode
            )

        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.import_mode_title))
            .setView(dialogView).setPositiveButton(R.string.ok) { _, _ ->
                val mode = if (switchMerge.isChecked) ImportMode.MERGE else ImportMode.REPLACE
                performImport(uri, mode)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun performImport(uri: android.net.Uri, mode: ImportMode) {
        val success = BackupManager.importFromZip(uri, mode)
        if (!success) {
            NotificationUtils.showInfoSnackBar(
                this,
                getString(R.string.import_failed),
                Snackbar.LENGTH_LONG,
            )
        } else {
            setupViewPager()
            buildImportSuccessDialog()
        }
    }

    private fun buildImportSuccessDialog() {
        MaterialAlertDialogBuilder(this).setMessage(
                getString(R.string.import_success, DataManager.instance.activeWebsitesCount)
            ).setPositiveButton(R.string.ok, null).show()
    }

    private fun buildAddWebsiteDialog() {
        showInputDialog(
            titleRes = R.string.add_webapp,
            hintRes = R.string.url,
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI,
        ) { url ->
            val urlWithProtocol = if (url.startsWith("https://") || url.startsWith("http://")) url
            else "https://$url"
            val newSite = WebApp(urlWithProtocol)
            newSite.order = DataManager.instance.incrementedOrder

            val currentPage = viewPager?.currentItem ?: 0
            val groups = DataManager.instance.sortedGroups
            if (groups.isNotEmpty() && currentPage < groups.size) {
                newSite.groupUuid = groups[currentPage].uuid
            }

            DataManager.instance.addWebsite(newSite)
            refreshCurrentPages()

            val settingsIntent = Intent(this, WebAppSettingsActivity::class.java)
            settingsIntent.putExtra(Const.INTENT_WEBAPP_UUID, newSite.uuid)
            settingsIntent.putExtra(Const.INTENT_AUTO_FETCH, true)
            startActivity(settingsIntent)
        }
    }
}
