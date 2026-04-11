package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.ImportActivity
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
    private var pendingParsed: ParsedBackup? = null
    private var pendingGroupShare = false

    val importLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val parsed = pendingParsed ?: return@registerForActivityResult
            pendingParsed = null
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val selectedUuids = data.getStringArrayExtra(ImportActivity.RESULT_SELECTED_UUIDS)
                ?.toSet() ?: return@registerForActivityResult
            if (pendingGroupShare) {
                val selectedGroupUuids =
                    data.getStringArrayExtra(ImportActivity.RESULT_SELECTED_GROUP_UUIDS)
                        ?.toSet() ?: return@registerForActivityResult
                performGroupSharedImport(parsed, selectedUuids, selectedGroupUuids)
            } else {
                val groupUuid = data.getStringExtra(ImportActivity.RESULT_GROUP_UUID)
                performSharedImport(parsed, selectedUuids, groupUuid)
            }
        }

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
                BackupManager.PAYLOAD_GROUP_SHARE -> launchImportActivity(parsed, groupShare = true)
                else -> launchImportActivity(parsed, groupShare = false)
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

    private fun launchImportActivity(parsed: ParsedBackup, groupShare: Boolean) {
        pendingParsed = parsed
        pendingGroupShare = groupShare
        ImportActivity.pendingBackup = parsed
        importLauncher.launch(
            Intent(activity, ImportActivity::class.java)
                .putExtra(ImportActivity.EXTRA_GROUP_SHARE, groupShare)
        )
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
