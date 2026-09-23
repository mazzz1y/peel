package wtf.mazy.peel.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.SessionHostRegistry
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.gecko.SandboxManager
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.dialog.BackupPasswordDialog
import wtf.mazy.peel.ui.dialog.ImportFlowController
import wtf.mazy.peel.ui.dialog.dismissOnDestroyOf
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.toast

class SettingsHubController(
    private val activity: AppCompatActivity,
    importActivity: Class<out Activity>,
) {

    private val exportLoader = LoadingDialogController(activity)
    private val importFlow = ImportFlowController(activity, importActivity)
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
            uri?.let { importFlow.showForUri(it) }
        }

    fun importBackup() {
        try {
            importLauncher.launch("*/*")
        } catch (_: ActivityNotFoundException) {
            activity.toast(R.string.no_filemanager, long = true)
        }
    }

    fun exportBackup() {
        try {
            exportLauncher.launch(BackupManager.buildExportFilename())
        } catch (_: ActivityNotFoundException) {
            activity.toast(R.string.no_filemanager, long = true)
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
        activity.toast(R.string.backup_save_failed)
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
        importFlow.onHostDestroy()
    }

    private fun performFullBackupExport(uri: Uri, password: CharArray?) {
        val encrypting = password != null
        runWithLoader(
            activity = activity,
            loader = exportLoader,
            showLoader = encrypting ||
                    DataManager.webApps.size >= BackupManager.LOADER_THRESHOLD,
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
            activity.toast(if (success) R.string.backup_saved else R.string.backup_save_failed)
        }
    }

    private suspend fun wipeStorage(includeSandbox: Boolean) {
        SessionHostRegistry.closeAllSessions()
        SandboxManager.clearNonSandboxData()
        if (includeSandbox) SandboxManager.clearAllSandboxData(activity)
    }

    private fun clearBrowsingData(includeSandbox: Boolean) {
        SessionHostRegistry.finishBrowsers()
        App.appScope.launch { wipeStorage(includeSandbox) }
    }

    private fun clearTranslationModels() {
        activity.lifecycleScope.launch {
            TranslationLanguages.deleteAllModels()
        }
    }

    private fun performFactoryReset() {
        SessionHostRegistry.finishBrowsers()

        App.appScope.launch {
            wipeStorage(includeSandbox = true)
            TranslationLanguages.deleteAllModels()
            DataManager.deleteWebApps(DataManager.webApps.map { it.uuid })
            DataManager.groups.forEach { group ->
                DataManager.removeGroup(group, ungroupApps = false)
            }

            DataManager.setGlobalSettings(
                DataManager.globalSettings.copy(settings = WebAppSettings.createWithDefaults())
            )
        }
    }
}
