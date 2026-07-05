package wtf.mazy.peel.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import wtf.mazy.peel.BuildConfig
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.dialog.ImportDialogHelper
import wtf.mazy.peel.util.NotificationUtils

class SettingsHubActions(private val activity: AppCompatActivity) {

    private val exportLoader = LoadingDialogController(activity)
    private val importDialogHelper = ImportDialogHelper(activity)

    private val exportLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)
        ) { uri ->
            uri?.let { performFullBackupExport(it) }
        }

    private val importLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let { importDialogHelper.showForUri(it) }
        }

    fun importBackup() {
        try {
            importLauncher.launch("*/*")
        } catch (_: ActivityNotFoundException) {
            NotificationUtils.showToast(
                activity,
                activity.getString(R.string.no_filemanager),
                Toast.LENGTH_LONG
            )
        }
    }

    fun exportBackup() {
        try {
            exportLauncher.launch(BackupManager.buildExportFilename())
        } catch (_: ActivityNotFoundException) {
            NotificationUtils.showToast(
                activity,
                activity.getString(R.string.no_filemanager),
                Toast.LENGTH_LONG
            )
        }
    }

    fun clearData() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_clear_data, null)
        val switchBrowsing =
            dialogView.findViewById<MaterialSwitch>(R.id.switchClearBrowsingData)
        val switchSandbox =
            dialogView.findViewById<MaterialSwitch>(R.id.switchClearSandboxData)
        val switchTranslations =
            dialogView.findViewById<MaterialSwitch>(R.id.switchClearTranslations)
        val switchFactory =
            dialogView.findViewById<MaterialSwitch>(R.id.switchFactoryReset)

        switchBrowsing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && switchFactory.isChecked) switchFactory.isChecked = false
            switchSandbox.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) switchSandbox.isChecked = false
        }

        switchFactory.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (switchBrowsing.isChecked) switchBrowsing.isChecked = false
                if (switchTranslations.isChecked) switchTranslations.isChecked = false
            }
        }

        switchTranslations.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && switchFactory.isChecked) switchFactory.isChecked = false
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.clear_data)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (switchFactory.isChecked) {
                    performFactoryReset()
                } else {
                    if (switchBrowsing.isChecked) clearBrowsingData(switchSandbox.isChecked)
                    if (switchTranslations.isChecked) clearTranslationModels()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showAbout() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.aboutVersion).text =
            activity.getString(R.string.about_version, BuildConfig.VERSION_NAME)
        view.findViewById<TextView>(R.id.aboutEngine).text =
            activity.getString(R.string.about_engine, BuildConfig.GECKOVIEW_VERSION)
        view.findViewById<View>(R.id.aboutGithub).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()))
        }
        view.findViewById<View>(R.id.aboutFdroid).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, FDROID_URL.toUri()))
        }
        view.findViewById<View>(R.id.aboutGooglePlay).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, GOOGLE_PLAY_URL.toUri()))
        }
        view.findViewById<View>(R.id.aboutLicense).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, LICENSE_URL.toUri()))
        }
        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .show()
    }

    fun onDestroy() {
        exportLoader.dismiss()
        importDialogHelper.onHostDestroy()
    }

    private fun performFullBackupExport(uri: Uri) {
        runWithLoader(
            activity = activity,
            loader = exportLoader,
            showLoader = DataManager.instance.getWebsites().size >= BackupManager.LOADER_THRESHOLD,
            loadingRes = R.string.preparing_export,
            ioTask = { BackupManager.exportFullBackup(uri) },
        ) { success ->
            NotificationUtils.showToast(
                activity,
                activity.getString(if (success) R.string.backup_saved else R.string.backup_save_failed),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun clearBrowsingData(includeSandbox: Boolean) {
        BrowserActivity.finishAll()
        SandboxManager.clearNonSandboxData()
        if (includeSandbox) {
            SandboxManager.clearAllSandboxData(activity)
        }
    }

    private fun clearTranslationModels() {
        activity.lifecycleScope.launch {
            TranslationLanguages.deleteAllModels()
        }
    }

    private fun performFactoryReset() {
        BrowserActivity.finishAll()
        SandboxManager.clearNonSandboxData()
        SandboxManager.clearAllSandboxData(activity)

        activity.lifecycleScope.launch {
            TranslationLanguages.deleteAllModels()
            DataManager.instance.getWebsites().forEach { webapp ->
                DataManager.instance.cleanupAndRemoveWebApp(webapp.uuid, activity)
            }
            DataManager.instance.getGroups().forEach { group ->
                DataManager.instance.removeGroup(group, ungroupApps = false)
            }

            DataManager.instance.setDefaultSettings(
                DataManager.instance.defaultSettings.also {
                    it.settings = WebAppSettings.createWithDefaults()
                }
            )
        }
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/mazzz1y/peel"
        private const val FDROID_URL = "https://mazzz1y.github.io/fdroid/repo"
        private const val GOOGLE_PLAY_URL =
            "https://play.google.com/store/apps/details?id=wtf.mazy.peel"
        private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.txt"
    }
}
