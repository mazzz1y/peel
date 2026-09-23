package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.net.Uri
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ImportMode
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.model.backup.BackupSource
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.importmapping.ImportMappingContract
import wtf.mazy.peel.util.toast
import javax.crypto.BadPaddingException

class ImportFlowController(
    private val activity: AppCompatActivity,
    mappingActivity: Class<out Activity>,
) {

    private val loader = LoadingDialogController(activity)

    private val mappingLauncher =
        activity.registerForActivityResult(ImportMappingContract(mappingActivity)) { selection ->
            when (selection) {
                is ImportMappingContract.Selection.Groups ->
                    performGroupSharedImport(
                        selection.parsed,
                        selection.appUuids,
                        selection.groupUuids
                    )

                is ImportMappingContract.Selection.Apps ->
                    performSharedImport(
                        selection.parsed,
                        selection.appUuids,
                        selection.destinationGroupUuid
                    )

                null -> Unit
            }
        }

    fun showForUri(uri: Uri) {
        runWithLoader(
            activity = activity,
            loader = loader,
            showLoader = true,
            loadingRes = R.string.importing,
            ioTask = { BackupManager.readBackupSource(uri) },
        ) { source ->
            when (source) {
                is BackupSource.Plain -> dispatchParsed(source.parsed)
                is BackupSource.Protected -> promptForPassword(source)
                BackupSource.Damaged, BackupSource.NotABackup -> showError()
            }
        }
    }

    private fun dispatchParsed(parsed: ParsedBackup) {
        when (parsed.backupData.payloadType) {
            BackupManager.PAYLOAD_FULL -> showFullBackupDialog(parsed)
            BackupManager.PAYLOAD_GROUP_SHARE -> launchImportActivity(parsed, groupShare = true)
            else -> launchImportActivity(parsed, groupShare = false)
        }
    }

    private fun promptForPassword(source: BackupSource.Protected) {
        BackupPasswordDialog.requestExisting(
            activity,
            onResult = { password -> decryptAndDispatch(source, password) },
            onCancel = {},
        )
    }

    private fun decryptAndDispatch(source: BackupSource.Protected, password: CharArray) {
        runWithLoader(
            activity = activity,
            loader = loader,
            showLoader = true,
            loadingRes = R.string.backup_decrypting,
            ioTask = {
                try {
                    BackupManager.decryptBackup(source, password)
                        ?.let(DecryptOutcome::Ok)
                        ?: DecryptOutcome.Damaged
                } catch (_: BadPaddingException) {
                    DecryptOutcome.WrongPassword
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    DecryptOutcome.Damaged
                } catch (_: OutOfMemoryError) {
                    DecryptOutcome.Damaged
                } finally {
                    password.fill('\u0000')
                }
            },
        ) { outcome ->
            when (outcome) {
                is DecryptOutcome.Ok -> dispatchParsed(outcome.parsed)
                DecryptOutcome.WrongPassword -> {
                    activity.toast(R.string.backup_password_wrong, long = true)
                    promptForPassword(source)
                }

                DecryptOutcome.Damaged -> showError()
            }
        }
    }

    private sealed interface DecryptOutcome {
        data class Ok(val parsed: ParsedBackup) : DecryptOutcome
        data object WrongPassword : DecryptOutcome
        data object Damaged : DecryptOutcome
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
            .dismissOnDestroyOf(activity)
    }

    private fun launchImportActivity(parsed: ParsedBackup, groupShare: Boolean) {
        mappingLauncher.launch(ImportMappingContract.Request(parsed, groupShare))
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
            activity.toast(R.string.import_count_message, imported)
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
            activity.toast(R.string.import_count_message, imported)
        }
    }

    private fun showError() {
        activity.toast(R.string.import_failed, long = true)
    }

    private fun showSuccessDialog() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(
                activity.getString(
                    R.string.import_success,
                    DataManager.webAppCount,
                )
            )
            .setPositiveButton(R.string.ok, null)
            .show()
            .dismissOnDestroyOf(activity)
    }
}
