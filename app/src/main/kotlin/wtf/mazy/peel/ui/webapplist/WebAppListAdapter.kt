package wtf.mazy.peel.ui.webapplist

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EntityCloner
import wtf.mazy.peel.model.Protection
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.searched
import wtf.mazy.peel.shortcut.Shortcuts
import wtf.mazy.peel.ui.ProtectionGate
import wtf.mazy.peel.ui.common.ShareSecretsDialog
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowListener
import wtf.mazy.peel.ui.entitylist.EntitySelectionController
import wtf.mazy.peel.ui.entitylist.PendingDeletes
import wtf.mazy.peel.ui.entitylist.binders.WebAppBinder
import wtf.mazy.peel.ui.entitylist.scheduleEntityDelete
import wtf.mazy.peel.util.ActivityRoutes
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.BrowserLauncher.launch
import wtf.mazy.peel.util.Const

class WebAppListAdapter(
    activity: AppCompatActivity,
    private val selection: EntitySelectionController<WebApp>? = null,
) : EntityListAdapter<WebApp, WebAppRowHolder>(
    binder = WebAppBinder,
    actions = WebAppRowListener(activity, selection),
) {

    var groupFilter: String? = null
    var searchQuery: String = ""
    var showGroupLabels: Boolean = false

    override fun layoutRes(): Int = R.layout.web_app_list_item
    override fun createViewHolder(view: View): WebAppRowHolder = WebAppRowHolder(view)

    override fun bindRow(holder: WebAppRowHolder, row: EntityRow<WebApp>) {
        holder.bind(row.entity, row.tertiaryText)
    }

    private fun buildRows(): List<EntityRow<WebApp>> {
        val all = when (groupFilter) {
            null -> DataManager.sortedWebApps
            WebAppListFragment.UNGROUPED_FILTER ->
                DataManager.webAppsInGroup(null)

            else -> DataManager.webAppsInGroup(groupFilter)
        }
        val pending = PendingDeletes.webApps
        val afterPending = if (pending.isEmpty()) all else all.filterNot { it.uuid in pending }
        val filtered = afterPending.searched(
            searchQuery,
            DataManager.groups.associate { it.uuid to it.title },
        )
        val inSelectionMode = selection?.isActive == true
        return filtered.map { app ->
            EntityRow(
                entity = app,
                selected = selection?.isSelected(app.uuid) == true,
                inSelectionMode = inSelectionMode,
                tertiaryText = if (showGroupLabels)
                    app.groupUuid?.let { DataManager.group(it)?.title }
                else null,
            )
        }
    }

    fun updateWebAppList(): Boolean {
        val rows = buildRows()
        submitRows(rows)
        return rows.isEmpty()
    }

    private class WebAppRowListener(
        private val activity: AppCompatActivity,
        private val selection: EntitySelectionController<WebApp>?,
    ) : EntityRowListener<WebApp> {

        override fun onItemClick(item: WebApp) {
            val webApp = DataManager.webApp(item.uuid) ?: return
            launch(webApp, activity, fromMenu = true)
        }

        override fun onItemIconClick(item: WebApp) {
            selection?.enter(item.uuid)
        }

        override fun onItemMenu(view: View, item: WebApp) {
            showPopupMenu(view, item)
        }

        private fun showPopupMenu(view: View, shown: WebApp) {
            val popup = PopupMenu(activity, view)
            popup.menuInflater.inflate(R.menu.webapp_item_menu, popup.menu)

            val groups = DataManager.sortedGroups
            if (groups.isNotEmpty()) {
                val subMenu = popup.menu.addSubMenu(
                    0, 0, 20, activity.getString(R.string.move_to_group),
                )
                groups.forEachIndexed { index, group ->
                    subMenu.add(0, MENU_GROUP_BASE + index, index, group.title)
                }
                subMenu.add(
                    0,
                    MENU_GROUP_BASE + groups.size,
                    groups.size,
                    activity.getString(R.string.ungrouped),
                )
            }

            popup.setOnMenuItemClickListener { menuItem ->
                val webApp = DataManager.webApp(shown.uuid) ?: return@setOnMenuItemClickListener true
                when (menuItem.itemId) {
                    R.id.action_settings -> {
                        openSettings(webApp); true
                    }

                    R.id.action_add_to_home -> {
                        Shortcuts.createShortcut(webApp, activity); true
                    }

                    R.id.action_share -> {
                        shareWebApp(webApp); true
                    }

                    R.id.action_clone -> {
                        cloneWebApp(webApp); true
                    }

                    R.id.action_delete -> {
                        deleteWebApp(webApp); true
                    }

                    else -> {
                        val groupIndex = menuItem.itemId - MENU_GROUP_BASE
                        if (groupIndex in 0..groups.size) {
                            val targetGroupUuid =
                                if (groupIndex < groups.size) groups[groupIndex].uuid else null
                            ProtectionGate.require(activity, Protection.of(webApp)) {
                                activity.lifecycleScope.launch {
                                    DataManager.moveWebAppsToGroup(
                                        listOf(webApp.uuid),
                                        targetGroupUuid,
                                    )
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            popup.show()
        }

        private fun openSettings(webApp: WebApp) {
            val intent = Intent(activity, ActivityRoutes.webAppSettings)
            intent.putExtra(Const.INTENT_WEBAPP_UUID, webApp.uuid)
            (activity as? WebAppListHost)?.launchSettings(intent)
                ?: activity.startActivity(intent)
        }

        private fun cloneWebApp(webApp: WebApp) {
            App.appScope.launch {
                EntityCloner.cloneWebApp(webApp)
            }
        }

        private fun shareWebApp(webApp: WebApp) {
            val shareHost = requireNotNull(activity as? WebAppShareHost) {
                "shareWebApp requires the host activity to implement WebAppShareHost"
            }
            ShareSecretsDialog.confirmForWebApps(activity, listOf(webApp)) { includeSecrets ->
                shareHost.shareApps(listOf(webApp), includeSecrets)
            }
        }

        private fun deleteWebApp(webApp: WebApp) {
            val refreshHost = activity as? WebAppShareHost
            scheduleEntityDelete(
                activity = activity,
                uuids = listOf(webApp.uuid),
                message = activity.getString(R.string.x_was_removed, webApp.title),
                pendingDeleteSet = PendingDeletes.webApps,
                onPendingChanged = { refreshHost?.refreshWebAppList() },
                commitDelete = { uuids -> DataManager.deleteWebApps(uuids) },
            )
        }

        companion object {
            private const val MENU_GROUP_BASE = 10000
        }
    }
}
