package wtf.mazy.peel.ui.webapplist

import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.common.ShareSecretsDialog
import wtf.mazy.peel.ui.entitylist.EntitySelectionActions
import wtf.mazy.peel.ui.entitylist.MoveTarget

class WebAppSelectionActions(
    private val activity: AppCompatActivity,
    private val shareHost: WebAppShareHost,
) : EntitySelectionActions<WebApp> {

    override val pendingDeleteSet: MutableSet<String>
        get() = DataManager.instance.pendingDeleteWebAppUuids

    override fun confirmShare(items: List<WebApp>, onConfirm: (Boolean) -> Unit) {
        ShareSecretsDialog.confirmForWebApps(activity, items, onConfirm)
    }

    override fun share(items: List<WebApp>, includeSecrets: Boolean) {
        shareHost.shareApps(items, includeSecrets)
    }

    override fun deleteTitle(): String = activity.getString(R.string.remove_apps_title)
    override fun deleteMessage(count: Int): String =
        activity.getString(R.string.remove_apps_confirm, count)

    override fun deletedToast(count: Int): String =
        activity.getString(R.string.n_apps_removed, count)

    override suspend fun commitDelete(uuids: List<String>) {
        DataManager.instance.deleteWebApps(uuids, activity)
    }

    override val moveTargets: List<MoveTarget>
        get() {
            val groups = DataManager.instance.sortedGroups
            if (groups.isEmpty()) return emptyList()
            return buildList {
                groups.forEach { add(MoveTarget(it.title, it.uuid)) }
                add(MoveTarget(activity.getString(R.string.ungrouped), null))
            }
        }

    override suspend fun commitMove(uuids: List<String>, targetGroupUuid: String?) {
        DataManager.instance.moveWebAppsToGroup(uuids, targetGroupUuid)
    }
}
