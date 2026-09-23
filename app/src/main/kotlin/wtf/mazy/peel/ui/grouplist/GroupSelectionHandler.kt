package wtf.mazy.peel.ui.grouplist

import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.ShareSecretsDialog
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.entitylist.EntitySelectionHandler
import wtf.mazy.peel.ui.entitylist.PendingDeletes
import wtf.mazy.peel.util.toast

class GroupSelectionHandler(
    private val activity: AppCompatActivity,
    private val transferLoader: LoadingDialogController,
) : EntitySelectionHandler<WebAppGroup> {

    override val pendingDeleteSet: MutableSet<String>
        get() = PendingDeletes.groups

    override fun confirmShare(items: List<WebAppGroup>, onConfirm: (Boolean) -> Unit) {
        val webApps = items.flatMap { DataManager.webAppsInGroup(it.uuid) }
        ShareSecretsDialog.confirmForGroupsAndApps(activity, items, webApps, onConfirm)
    }

    override fun share(items: List<WebAppGroup>, includeSecrets: Boolean) {
        val webApps = items.flatMap { DataManager.webAppsInGroup(it.uuid) }
        runWithLoader(
            activity = activity,
            loader = transferLoader,
            showLoader = items.size + webApps.size >= BackupManager.LOADER_THRESHOLD,
            loadingRes = R.string.preparing_export,
            ioTask = { BackupManager.buildGroupShareFile(items, webApps, includeSecrets) },
        ) { file ->
            if (file == null || !BackupManager.launchShareChooser(activity, file)) {
                activity.toast(R.string.export_share_failed, long = true)
            }
        }
    }

    override fun deleteTitle(): String = activity.getString(R.string.delete_group_title)
    override fun deleteMessage(count: Int): String =
        activity.getString(R.string.remove_groups_confirm, count)

    override fun deletedToast(count: Int): String =
        activity.getString(R.string.n_groups_removed, count)

    override suspend fun commitDelete(uuids: List<String>) {
        val groups = DataManager.groups.filter { it.uuid in uuids }
        groups.forEach { DataManager.removeGroup(it, ungroupApps = false) }
    }
}
