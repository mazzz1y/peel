package wtf.mazy.peel.ui.dialog

import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ImportMode
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.importmapping.ImportMappingAdapter
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
        val websites = parsed.backupData.websites
        val icons = parsed.icons
        val isEmpty = websites.isEmpty()

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_import_mapping, null)
        val descriptionView = dialogView.findViewById<TextView>(R.id.import_mapping_description)
        val dropdown =
            dialogView.findViewById<AutoCompleteTextView>(R.id.destination_group_dropdown)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.import_app_list)
        val emptyView = dialogView.findViewById<View>(R.id.import_mapping_empty)

        descriptionView.text = activity.getString(
            R.string.import_mapping_description,
            websites.size,
        )

        val groups = DataManager.instance.sortedGroups
        val groupValues = mutableListOf<String?>(null)
        val groupLabels =
            mutableListOf(activity.getString(R.string.import_target_none))
        groups.forEach { group ->
            groupValues.add(group.uuid)
            groupLabels.add(group.title)
        }
        groupValues.add(CREATE_GROUP_SENTINEL)
        groupLabels.add(activity.getString(R.string.import_group_mode_new))

        val dropdownAdapter =
            ArrayAdapter(activity, android.R.layout.simple_list_item_1, groupLabels)
        dropdown.setAdapter(dropdownAdapter)
        dropdown.setText(groupLabels[0], false)

        var selectedGroupUuid: String? = null

        dropdown.setOnItemClickListener { _, _, position, _ ->
            val value = groupValues[position]
            if (value == CREATE_GROUP_SENTINEL) {
                dropdown.setText(
                    selectedGroupUuid?.let { uuid ->
                        groupLabels.getOrNull(groupValues.indexOf(uuid))
                    } ?: groupLabels[0],
                    false,
                )
                activity.showSandboxInputDialog(
                    titleRes = R.string.add_group,
                    hintRes = R.string.group_name_hint,
                ) { result ->
                    val title = result.text.trim()
                    if (title.isEmpty()) return@showSandboxInputDialog
                    val group = WebAppGroup(
                        title = title,
                        order = DataManager.instance.getGroups().size,
                    )
                    group.isUseContainer = result.sandbox
                    group.isEphemeralSandbox = result.ephemeral
                    DataManager.instance.addGroup(group)
                    selectedGroupUuid = group.uuid

                    groupValues.add(groupValues.size - 1, group.uuid)
                    groupLabels.add(groupLabels.size - 1, group.title)
                    dropdownAdapter.clear()
                    dropdownAdapter.addAll(groupLabels)
                    dropdownAdapter.filter.filter(null)
                    dropdown.setText(group.title, false)
                }
                return@setOnItemClickListener
            }
            selectedGroupUuid = value
        }

        val selectedUuids = websites.mapTo(mutableSetOf()) { it.uuid }
        val adapter = ImportMappingAdapter(websites, icons, selectedUuids)
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        val maxListHeight = (activity.resources.displayMetrics.heightPixels * 0.4f).toInt()
        recycler.post {
            if (recycler.height > maxListHeight) {
                recycler.layoutParams =
                    recycler.layoutParams.apply { height = maxListHeight }
            }
        }

        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE

        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.import_mapping_title)
                .setView(dialogView)
                .setPositiveButton(R.string.import_btn) { _, _ ->
                    performSharedImport(parsed, adapter.selectedUuids, selectedGroupUuid)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = !isEmpty
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
            NotificationUtils.showInfoSnackBar(
                activity,
                activity.getString(R.string.import_count_message, imported),
                Snackbar.LENGTH_SHORT,
            )
        }
    }

    private fun showError() {
        NotificationUtils.showInfoSnackBar(
            activity,
            activity.getString(R.string.import_failed),
            Snackbar.LENGTH_LONG,
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

    companion object {
        private const val CREATE_GROUP_SENTINEL = "__create_group__"
    }
}
