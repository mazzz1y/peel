package wtf.mazy.peel.activities

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.SessionContextRegistry
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.dialog.BackupPasswordDialog
import wtf.mazy.peel.ui.dialog.ImportDialogHelper
import wtf.mazy.peel.ui.dialog.dismissOnDestroyOf
import wtf.mazy.peel.util.NotificationUtils

class SettingsHubActions(private val activity: AppCompatActivity) {

    private val exportLoader = LoadingDialogController(activity)
    private val importDialogHelper = ImportDialogHelper(activity, ImportActivity::class.java)
    private var pendingExportUri: Uri? = null

    private val exportLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)
        ) { uri ->
            uri?.let { askForPasswordThenExport(it) }
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

    // Asked for after the picker returns: a password held across that boundary is lost
    // to an activity restart, which would silently write the backup unprotected.
    private fun askForPasswordThenExport(uri: Uri) {
        pendingExportUri = uri
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_protect_title)
            .setMessage(R.string.backup_protect_message)
            .setPositiveButton(R.string.backup_protect_set) { _, _ ->
                BackupPasswordDialog.requestNew(
                    activity,
                    onResult = { password ->
                        pendingExportUri = null
                        performFullBackupExport(uri, password)
                    },
                    onCancel = { abandonExport() },
                )
            }
            .setNegativeButton(R.string.backup_protect_skip) { _, _ ->
                pendingExportUri = null
                performFullBackupExport(uri, null)
            }
            .setOnCancelListener { abandonExport() }
            .show()
            .dismissOnDestroyOf(activity)
    }

    private fun abandonExport(notify: Boolean = true) {
        val uri = pendingExportUri ?: return
        pendingExportUri = null
        discardExportDocument(uri)
        if (notify) notifyExportFailed()
    }

    private fun discardExportDocument(uri: Uri) {
        val resolver = activity.applicationContext.contentResolver
        activity.lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                DocumentsContract.deleteDocument(resolver, uri)
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyExportFailed() {
        NotificationUtils.showToast(
            activity,
            activity.getString(R.string.backup_save_failed),
            Toast.LENGTH_SHORT,
        )
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

    fun onDestroy() {
        abandonExport(notify = false)
        exportLoader.dismiss()
        importDialogHelper.onHostDestroy()
    }

    private fun performFullBackupExport(uri: Uri, password: CharArray?) {
        val encrypting = password != null
        runWithLoader(
            activity = activity,
            loader = exportLoader,
            showLoader = encrypting ||
                    DataManager.instance.getWebsites().size >= BackupManager.LOADER_THRESHOLD,
            loadingRes = if (encrypting) R.string.backup_encrypting else R.string.preparing_export,
            ioTask = {
                var success = false
                try {
                    success = BackupManager.exportFullBackup(uri, password)
                    success
                } finally {
                    password?.fill('\u0000')
                    if (!success) discardExportDocument(uri)
                }
            },
        ) { success ->
            NotificationUtils.showToast(
                activity,
                activity.getString(if (success) R.string.backup_saved else R.string.backup_save_failed),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private suspend fun wipeStorage(includeSandbox: Boolean) {
        SessionContextRegistry.closeAllSessions()
        SandboxManager.clearNonSandboxData()
        if (includeSandbox) SandboxManager.clearAllSandboxData(activity)
    }

    private fun clearBrowsingData(includeSandbox: Boolean) {
        BrowserActivity.finishAll()
        DataManager.instance.appScope.launch { wipeStorage(includeSandbox) }
    }

    private fun clearTranslationModels() {
        activity.lifecycleScope.launch {
            TranslationLanguages.deleteAllModels()
        }
    }

    private fun performFactoryReset() {
        BrowserActivity.finishAll()

        DataManager.instance.appScope.launch {
            wipeStorage(includeSandbox = true)
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
}
