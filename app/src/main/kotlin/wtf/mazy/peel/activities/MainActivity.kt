package wtf.mazy.peel.activities

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.AddWebsiteDialogueBinding
import wtf.mazy.peel.fragments.webapplist.WebAppListFragment
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.EntryPointUtils.entryPointReached
import wtf.mazy.peel.util.NotificationUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webAppListFragment: WebAppListFragment

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri
            ->
            uri?.let {
                DataManager.instance.saveDefaultSettings()
                if (!DataManager.instance.exportToZip(it)) {
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
            uri?.let {
                val success = DataManager.instance.importFromZip(it)

                if (!success) {
                    NotificationUtils.showInfoSnackBar(
                        this,
                        getString(R.string.import_failed),
                        Snackbar.LENGTH_LONG,
                    )
                } else {
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    DataManager.instance.loadAppData()
                    updateWebAppList()
                    buildImportSuccessDialog()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webAppListFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container_view)
                as WebAppListFragment
        entryPointReached()

        val fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener { buildAddWebsiteDialog(getString(R.string.add_webapp)) }
        personalizeToolbar()
    }

    override fun onResume() {
        super.onResume()
        DataManager.instance.loadAppData()
        updateWebAppList()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Const.INTENT_BACKUP_RESTORED, false)) {
            updateWebAppList()

            buildImportSuccessDialog()
            intent.putExtra(Const.INTENT_BACKUP_RESTORED, false)
            intent.putExtra(Const.INTENT_REFRESH_NEW_THEME, false)
        }
        if (intent.getBooleanExtra(Const.INTENT_WEBAPP_CHANGED, false)) {
            updateWebAppList()
            intent.putExtra(Const.INTENT_WEBAPP_CHANGED, false)
        }
    }

    private fun updateWebAppList() {
        webAppListFragment.updateWebAppList()
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
        val switchSandboxed =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchSandboxed)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ -> clearData(switchSandboxed.isChecked) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearData(includeSandboxed: Boolean) {
        SandboxManager.clearNonSandboxData(this)
        if (includeSandboxed) {
            SandboxManager.clearAllSandboxData(this)
        }
    }

    private fun buildAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.gnu_license))
            .setPositiveButton(R.string.ok, null)
            .create()
            .show()
    }

    private fun buildImportSuccessDialog() {
        AlertDialog.Builder(this)
            .setMessage(
                getString(R.string.import_success, DataManager.instance.activeWebsitesCount))
            .setPositiveButton(R.string.ok, null)
            .create()
            .show()
    }

    private fun buildAddWebsiteDialog(title: String) {
        val localBinding = AddWebsiteDialogueBinding.inflate(layoutInflater)
        val dialog =
            AlertDialog.Builder(this@MainActivity)
                .setView(localBinding.root)
                .setTitle(title)
                .setPositiveButton(R.string.ok) { _: DialogInterface, _: Int ->
                    val url = localBinding.websiteUrl.text.toString().trim()
                    val urlWithProtocol =
                        if (url.startsWith("https://") || url.startsWith("http://")) url
                        else "https://$url"
                    val newSite = WebApp(urlWithProtocol)
                    newSite.order = DataManager.instance.incrementedOrder
                    DataManager.instance.addWebsite(newSite)

                    updateWebAppList()

                    val settingsIntent =
                        Intent(this@MainActivity, WebAppSettingsActivity::class.java)
                    settingsIntent.putExtra(Const.INTENT_WEBAPP_UUID, newSite.uuid)
                    settingsIntent.putExtra(Const.INTENT_AUTO_FETCH, true)
                    startActivity(settingsIntent)
                }
                .create()

        dialog.show()
        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = false
        localBinding.websiteUrl.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    okButton.isEnabled = !s.isNullOrBlank()
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
    }
}
