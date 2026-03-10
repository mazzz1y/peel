package wtf.mazy.peel.ui.dialog

import android.net.Uri
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ImportMode
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.util.NotificationUtils

class ImportDialogHelper(
    private val activity: AppCompatActivity,
    private val onImportComplete: () -> Unit,
) {

    private val loader = LoadingDialogController(activity)

    fun showForUri(uri: Uri) {
        activity.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { BackupManager.readBackup(uri) }
            if (parsed == null) {
                showError()
                return@launch
            }
            when (parsed.backupData.payloadType) {
                BackupManager.PAYLOAD_FULL -> showFullBackupDialog(parsed)
                else -> showSharedImportDialog(parsed)
            }
        }
    }

    fun onHostDestroy() {
        loader.dismiss()
    }

    private fun showFullBackupDialog(parsed: ParsedBackup) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_import_mode, null)
        val switchMerge =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchMergeMode
            )
        val messageView = dialogView.findViewById<TextView>(R.id.import_mode_message)
        messageView.text = activity.getString(
            R.string.import_mode_description,
            parsed.backupData.websites.size,
            parsed.backupData.groups.size,
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.import_mode_title)
            .setView(dialogView)
            .setPositiveButton(R.string.import_btn) { _, _ ->
                val mode = if (switchMerge.isChecked) ImportMode.MERGE else ImportMode.REPLACE
                val showLoader = parsed.backupData.websites.size >= BackupManager.LOADER_THRESHOLD
                if (showLoader) loader.show(R.string.importing)
                activity.lifecycleScope.launch {
                    val success = try {
                        withContext(Dispatchers.IO) {
                            BackupManager.importFullBackup(parsed, mode)
                        }
                    } finally {
                        if (showLoader) loader.dismiss()
                    }
                    if (!success) {
                        showError()
                    } else {
                        onImportComplete()
                        showSuccessDialog()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSharedImportDialog(parsed: ParsedBackup) {
        val sheet = ImportBottomSheetFragment()
        sheet.configure(
            parsed = parsed,
            onDismissed = { onImportComplete() },
            onImport = { selectedUuids, groupUuid ->
                performSharedImport(parsed, selectedUuids, groupUuid)
            },
        )
        sheet.show(activity.supportFragmentManager, ImportBottomSheetFragment.TAG)
    }

    private fun performSharedImport(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        destinationGroupUuid: String?,
    ) {
        if (selectedUuids.isEmpty()) return

        val showLoader = selectedUuids.size >= BackupManager.LOADER_THRESHOLD
        if (showLoader) loader.show(R.string.importing)
        activity.lifecycleScope.launch {
            val imported = try {
                withContext(Dispatchers.IO) {
                    BackupManager.importShared(parsed, selectedUuids, destinationGroupUuid)
                }
            } finally {
                if (showLoader) loader.dismiss()
            }
            onImportComplete()
            NotificationUtils.showToast(
                activity,
                activity.getString(R.string.import_count_message, imported),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun showError() {
        NotificationUtils.showToast(
            activity,
            activity.getString(R.string.import_failed),
            Toast.LENGTH_LONG,
        )
    }

    private fun showSuccessDialog() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(
                activity.getString(
                    R.string.import_success,
                    DataManager.instance.activeWebsitesCount,
                )
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
