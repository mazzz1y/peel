package wtf.mazy.peel.ui.dialog

import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ImportMode
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.util.NotificationUtils

class ImportDialogHelper(
    private val activity: AppCompatActivity,
) {

    private val loader = LoadingDialogController(activity)

    fun showForUri(uri: Uri) {
        runWithLoader(
            activity = activity,
            loader = loader,
            showLoader = true,
            loadingRes = R.string.importing,
            ioTask = { BackupManager.readBackup(uri) },
        ) { parsed ->
            if (parsed == null) {
                showError()
                return@runWithLoader
            }
            when (parsed.backupData.payloadType) {
                BackupManager.PAYLOAD_FULL -> showFullBackupDialog(parsed)
                BackupManager.PAYLOAD_GROUP_SHARE -> showGroupShareImportDialog(parsed)
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
                runWithLoader(
                    activity = activity,
                    loader = loader,
                    showLoader = parsed.backupData.websites.size >= BackupManager.LOADER_THRESHOLD,
                    loadingRes = R.string.importing,
                    ioTask = { BackupManager.importFullBackup(parsed, mode) },
                ) { success ->
                    if (!success) {
                        showError()
                    } else {
                        showSuccessDialog()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSharedImportDialog(parsed: ParsedBackup) {
        val sheet = ImportBottomSheetFragment()
        sheet.configure(parsed) { selectedUuids, groupUuid ->
            performSharedImport(parsed, selectedUuids, groupUuid)
        }
        sheet.show(activity.supportFragmentManager, ImportBottomSheetFragment.TAG)
    }

    private fun showGroupShareImportDialog(parsed: ParsedBackup) {
        val sheet = ImportBottomSheetFragment()
        sheet.configureGroupShare(parsed) { selectedUuids, selectedGroupUuids ->
            performGroupSharedImport(parsed, selectedUuids, selectedGroupUuids)
        }
        sheet.show(activity.supportFragmentManager, ImportBottomSheetFragment.TAG)
    }

    private fun performSharedImport(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        destinationGroupUuid: String?,
    ) {
        if (selectedUuids.isEmpty()) return

        runWithLoader(
            activity = activity,
            loader = loader,
            showLoader = selectedUuids.size >= BackupManager.LOADER_THRESHOLD,
            loadingRes = R.string.importing,
            ioTask = { BackupManager.importShared(parsed, selectedUuids, destinationGroupUuid) },
        ) { imported ->
            NotificationUtils.showToast(
                activity,
                activity.getString(R.string.import_count_message, imported),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun performGroupSharedImport(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        selectedGroupUuids: Set<String>,
    ) {
        if (selectedGroupUuids.isEmpty()) return

        val importSize = selectedUuids.size + selectedGroupUuids.size
        runWithLoader(
            activity = activity,
            loader = loader,
            showLoader = importSize >= BackupManager.LOADER_THRESHOLD,
            loadingRes = R.string.importing,
            ioTask = { BackupManager.importGroupShared(parsed, selectedUuids, selectedGroupUuids) },
        ) { imported ->
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
