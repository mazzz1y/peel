package wtf.mazy.peel.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.dialog.ImportDialogHelper
import wtf.mazy.peel.ui.dialog.showSandboxInputDialog
import wtf.mazy.peel.ui.toolbar.SearchModeController
import wtf.mazy.peel.ui.toolbar.SelectionModeController
import wtf.mazy.peel.ui.toolbar.ToolbarModeHost
import wtf.mazy.peel.ui.webapplist.GroupPagerAdapter
import wtf.mazy.peel.ui.webapplist.SelectionModeHost
import wtf.mazy.peel.ui.webapplist.WebAppListFragment
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils

class MainActivity :
    AppCompatActivity(),
    ToolbarModeHost,
    SelectionModeHost,
    DataManager.DataChangeListener {

    override lateinit var toolbar: MaterialToolbar
    override lateinit var fab: FloatingActionButton
    override var tabLayout: TabLayout? = null
    override var viewPager: ViewPager2? = null
    override val hostActivity: AppCompatActivity get() = this

    private var pagerAdapter: GroupPagerAdapter? = null
    private var lastGroupKeys: List<Pair<String, String>> = emptyList()
    private var lastShowUngrouped: Boolean = true
    private lateinit var exportLoader: LoadingDialogController

    private lateinit var searchController: SearchModeController
    private lateinit var selectionController: SelectionModeController

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri ->
            uri?.let { performFullBackupExport(it) }
        }

    private val importDialogHelper = ImportDialogHelper(this)

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importDialogHelper.showForUri(it) }
        }

    private val backPressCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                searchController.isActive -> searchController.exit()
                selectionController.isActive -> selectionController.exit()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DataManager.instance.loadAppData()

        toolbar = findViewById(R.id.toolbar)
        fab = findViewById(R.id.fab)
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        exportLoader = LoadingDialogController(this)

        searchController = SearchModeController(this)
        selectionController = SelectionModeController(this) { searchController.isActive }
        searchController.searchResultsList = findViewById(R.id.searchResultsList)
        searchController.searchEmptyState = findViewById(R.id.searchEmptyState)

        toolbar.setTitle(R.string.app_name)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { onFabClicked() }
        onBackPressedDispatcher.addCallback(this, backPressCallback)

        DataManager.instance.addListener(this)
        setupViewPager()
        handleIncomingBackupIntent(intent)
    }

    override fun onDataChanged() {
        if (searchController.isActive) {
            searchController.onDataChanged()
        } else {
            refreshCurrentPages()
        }
    }

    override fun onResume() {
        super.onResume()
        DataManager.instance.loadAppData()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Const.INTENT_BACKUP_RESTORED, false)) {
            setupViewPager()
            intent.putExtra(Const.INTENT_BACKUP_RESTORED, false)
            intent.putExtra(Const.INTENT_REFRESH_NEW_THEME, false)
        }
        intent.putExtra(Const.INTENT_WEBAPP_CHANGED, false)
        handleIncomingBackupIntent(intent)
    }

    override fun onDestroy() {
        DataManager.instance.removeListener(this)
        importDialogHelper.onHostDestroy()
        exportLoader.dismiss()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                searchController.enter()
                true
            }

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
                    NotificationUtils.showToast(this, getString(R.string.no_filemanager), Toast.LENGTH_LONG)
                    Log.e("MainActivity", "No file manager available for import", e)
                }
                true
            }

            R.id.action_export -> {
                try {
                    exportLauncher.launch(BackupManager.buildExportFilename())
                } catch (e: ActivityNotFoundException) {
                    NotificationUtils.showToast(this, getString(R.string.no_filemanager), Toast.LENGTH_LONG)
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

    override fun refreshCurrentPages() {
        val groups = DataManager.instance.sortedGroups
        val newGroupKeys = groups.map { it.uuid to it.title }
        val newShowUngrouped =
            if (groups.isEmpty()) true
            else DataManager.instance.activeWebsitesForGroup(null).isNotEmpty()

        if (lastGroupKeys != newGroupKeys || lastShowUngrouped != newShowUngrouped) {
            setupViewPager()
            return
        }

        for (i in 0 until (pagerAdapter?.itemCount ?: 0)) {
            val fragment = supportFragmentManager.findFragmentByTag("f$i")
            (fragment as? WebAppListFragment)?.updateWebAppList()
        }
    }

    private fun forEachFragment(action: (WebAppListFragment) -> Unit) {
        for (i in 0 until (pagerAdapter?.itemCount ?: 0)) {
            val fragment = supportFragmentManager.findFragmentByTag("f$i")
            (fragment as? WebAppListFragment)?.let(action)
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

    override val isInSelectionMode: Boolean get() = selectionController.isActive

    override fun isSelected(uuid: String) = selectionController.isSelected(uuid)

    override fun enterSelectionMode(uuid: String) = selectionController.enter(uuid)

    override fun toggleSelection(uuid: String) = selectionController.toggle(uuid)

    override fun dispatchSelectionEntered(toggledUuid: String) {
        if (searchController.isActive) {
            searchController.notifySelectionEntered(toggledUuid)
        } else {
            forEachFragment { it.animateEnterSelection(toggledUuid) }
        }
    }

    override fun dispatchSelectionToggled(uuid: String) {
        if (searchController.isActive) {
            searchController.notifySelectionToggled(uuid)
        } else {
            forEachFragment { it.animateSelectionToggled(uuid) }
        }
    }

    override fun dispatchSelectionExited(previouslySelected: Set<String>) {
        if (searchController.isActive) {
            searchController.notifySelectionExited(previouslySelected)
        } else {
            forEachFragment { it.animateExitSelection(previouslySelected) }
        }
    }

    override fun onSearchModeExited() {
        if (selectionController.isActive) {
            selectionController.reapplyToolbar()
            animateFabSwap(R.drawable.ic_baseline_share_24)
            forEachFragment { it.refreshSelectionState() }
        } else {
            applyNormalToolbar()
            animateFabSwap(R.drawable.ic_add_24dp)
            refreshCurrentPages()
        }
    }

    override fun applyNormalToolbar() {
        crossfadeToolbar {
            removeSearchViewFromToolbar()
            toolbar.menu.clear()
            menuInflater.inflate(R.menu.menu_main, toolbar.menu)
            toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            toolbar.setTitle(R.string.app_name)
        }
    }

    override fun crossfadeToolbar(swap: () -> Unit) {
        toolbar.animate().cancel()
        toolbar.animate()
            .alpha(0f)
            .setDuration(Const.ANIM_DURATION_FAST)
            .withEndAction {
                swap()
                toolbar.animate()
                    .alpha(1f)
                    .setDuration(Const.ANIM_DURATION_FAST)
                    .start()
            }
            .start()
    }

    override fun animateFabSwap(iconRes: Int) {
        fab.hide(object : FloatingActionButton.OnVisibilityChangedListener() {
            override fun onHidden(fab: FloatingActionButton) {
                fab.setImageResource(iconRes)
                fab.show()
            }
        })
    }

    override fun updateBackPressEnabled() {
        backPressCallback.isEnabled = searchController.isActive || selectionController.isActive
    }

    override fun shareApps(webApps: List<WebApp>, includeSecrets: Boolean) {
        runWithLoader(
            webApps.size,
            ioTask = { BackupManager.buildShareFile(webApps, includeSecrets) },
        ) { file ->
            if (file == null || !BackupManager.launchShareChooser(this, file)) {
                NotificationUtils.showToast(this, getString(R.string.export_share_failed), Toast.LENGTH_LONG)
            }
        }
    }

    private fun onFabClicked() {
        if (selectionController.isActive) {
            selectionController.onFabClicked()
        } else {
            buildAddWebsiteDialog()
        }
    }

    private fun handleIncomingBackupIntent(intent: Intent?) {
        val uri = extractBackupUri(intent) ?: return
        intent?.action = null
        intent?.data = null
        intent?.removeExtra(Intent.EXTRA_STREAM)
        importDialogHelper.showForUri(uri)
    }

    private fun extractBackupUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }

    private fun performFullBackupExport(uri: Uri) {
        runWithLoader(
            DataManager.instance.getWebsites().size,
            ioTask = { BackupManager.exportFullBackup(uri) },
        ) { success ->
            NotificationUtils.showToast(
                this,
                getString(if (success) R.string.backup_saved else R.string.backup_save_failed),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun <T> runWithLoader(
        elementCount: Int,
        ioTask: () -> T,
        onResult: (T) -> Unit,
    ) {
        val showLoader = elementCount >= BackupManager.LOADER_THRESHOLD
        if (showLoader) exportLoader.show(R.string.preparing_export)
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { ioTask() }
            } finally {
                if (showLoader) exportLoader.dismiss()
            }
            onResult(result)
        }
    }

    private fun buildAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.gnu_license))
            .setPositiveButton(R.string.ok, null)
            .show()
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

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (switchFactory.isChecked) {
                    performFactoryReset()
                } else if (switchBrowsing.isChecked) {
                    clearBrowsingData(switchSandbox.isChecked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            SandboxManager.wipeSandboxStorage(group.uuid)
            DataManager.instance.removeGroup(group, ungroupApps = false)
        }

        DataManager.instance.defaultSettings =
            DataManager.instance.defaultSettings.also {
                it.settings = wtf.mazy.peel.model.WebAppSettings.createWithDefaults()
            }
    }

    private fun buildAddWebsiteDialog() {
        showSandboxInputDialog(
            titleRes = R.string.add_webapp,
            hintRes = R.string.url,
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI,
        ) { result ->
            val url = result.text
            val urlWithProtocol =
                if (url.startsWith("https://") || url.startsWith("http://")) url
                else "https://$url"
            val newSite = WebApp(urlWithProtocol)
            newSite.order = DataManager.instance.incrementedOrder
            newSite.isUseContainer = result.sandbox
            newSite.isEphemeralSandbox = result.ephemeral

            val currentPage = viewPager?.currentItem ?: 0
            val groups = DataManager.instance.sortedGroups
            if (groups.isNotEmpty() && currentPage < groups.size) {
                newSite.groupUuid = groups[currentPage].uuid
            }

            DataManager.instance.addWebsite(newSite)

            val settingsIntent = Intent(this, WebAppSettingsActivity::class.java)
            settingsIntent.putExtra(Const.INTENT_WEBAPP_UUID, newSite.uuid)
            settingsIntent.putExtra(Const.INTENT_AUTO_FETCH, true)
            startActivity(settingsIntent)
        }
    }
}
