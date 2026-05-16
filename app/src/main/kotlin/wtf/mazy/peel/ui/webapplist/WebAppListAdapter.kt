package wtf.mazy.peel.ui.webapplist

import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.MainActivity
import wtf.mazy.peel.activities.WebAppSettingsActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.ui.common.ShareSecretsDialog
import wtf.mazy.peel.ui.common.Theming
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityListViewHolder
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.entitylist.EntitySelectionController
import wtf.mazy.peel.ui.entitylist.binders.WebAppBinder
import wtf.mazy.peel.ui.entitylist.scheduleEntityDelete
import wtf.mazy.peel.util.BrowserLauncher.launch
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.prettyBaseUrl

class WebAppListAdapter(
    activity: AppCompatActivity,
    private val selection: EntitySelectionController<WebApp>? = null,
) : EntityListAdapter<WebApp, WebAppListAdapter.ViewHolder>(
    binder = WebAppBinder,
    actions = WebAppItemActions(activity, selection),
    checkIconColor = Theming.colorPrimary(activity),
) {

    var groupFilter: String? = null
    var searchQuery: String = ""
    var showGroupLabels: Boolean = false

    class ViewHolder(itemView: View) : EntityListViewHolder(itemView) {
        val iconSandbox: ImageView = itemView.findViewById(R.id.iconSandbox)
        val iconEphemeral: ImageView = itemView.findViewById(R.id.iconEphemeral)
        override val indicators: List<ImageView> = listOf(iconSandbox, iconEphemeral)
        val titleView: TextView = itemView.findViewById(R.id.item_primary)
        val urlView: TextView = itemView.findViewById(R.id.item_secondary)
        val groupLabel: TextView = itemView.findViewById(R.id.item_tertiary)
    }

    override fun layoutRes(): Int = R.layout.web_app_list_item
    override fun createViewHolder(view: View): ViewHolder = ViewHolder(view)

    override fun bindRow(holder: ViewHolder, row: EntityRow<WebApp>) {
        val app = row.entity
        holder.titleView.text = app.title
        holder.urlView.text = prettyBaseUrl(app.baseUrl)

        holder.groupLabel.text = row.tertiaryText
        holder.groupLabel.visibility = if (row.tertiaryText != null) View.VISIBLE else View.GONE

        holder.iconSandbox.visibility = if (app.isUseContainer) View.VISIBLE else View.GONE
        holder.iconEphemeral.visibility =
            if (app.isUseContainer && app.isEphemeralSandbox) View.VISIBLE else View.GONE
    }

    private fun buildRows(): List<EntityRow<WebApp>> {
        val all = when (groupFilter) {
            null -> DataManager.instance.activeWebsites
            WebAppListFragment.UNGROUPED_FILTER ->
                DataManager.instance.activeWebsitesForGroup(null)

            else -> DataManager.instance.activeWebsitesForGroup(groupFilter)
        }
        val pending = DataManager.instance.pendingDeleteWebAppUuids
        val afterPending = if (pending.isEmpty()) all else all.filterNot { it.uuid in pending }
        val filtered = if (searchQuery.isBlank()) {
            afterPending
        } else {
            val query = searchQuery.lowercase()
            val groupNames = DataManager.instance.sortedGroups.associate { it.uuid to it.title }
            afterPending.filter { app ->
                app.title.lowercase().contains(query) ||
                        app.baseUrl.lowercase().contains(query) ||
                        groupNames[app.groupUuid]?.lowercase()?.contains(query) == true
            }
        }
        val inSelectionMode = selection?.isActive == true
        return filtered.map { app ->
            EntityRow(
                entity = app,
                selected = selection?.isSelected(app.uuid) == true,
                inSelectionMode = inSelectionMode,
                tertiaryText = if (showGroupLabels)
                    app.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
                else null,
            )
        }
    }

    fun updateWebAppList(): Boolean {
        val rows = buildRows()
        submitRows(rows)
        return rows.isEmpty()
    }

    private class WebAppItemActions(
        private val activity: AppCompatActivity,
        private val selection: EntitySelectionController<WebApp>?,
    ) : EntityRowActions<WebApp> {

        override fun onItemClick(item: WebApp) {
            launch(item, activity, fromMenu = true)
        }

        override fun onItemIconClick(item: WebApp) {
            selection?.enter(item.uuid)
        }

        override fun onItemMenu(view: View, item: WebApp) {
            showPopupMenu(view, item)
        }

        private fun showPopupMenu(view: View, webapp: WebApp) {
            val popup = PopupMenu(activity, view)
            popup.menuInflater.inflate(R.menu.webapp_item_menu, popup.menu)

            val groups = DataManager.instance.sortedGroups
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
                when (menuItem.itemId) {
                    R.id.action_settings -> {
                        openSettings(webapp); true
                    }

                    R.id.action_add_to_home -> {
                        ShortcutHelper.createShortcut(webapp, activity); true
                    }

                    R.id.action_share -> {
                        shareWebApp(webapp); true
                    }

                    R.id.action_clone -> {
                        cloneWebApp(webapp); true
                    }

                    R.id.action_delete -> {
                        deleteWebApp(webapp); true
                    }

                    else -> {
                        val groupIndex = menuItem.itemId - MENU_GROUP_BASE
                        if (groupIndex in 0..groups.size) {
                            val targetGroupUuid =
                                if (groupIndex < groups.size) groups[groupIndex].uuid else null
                            DataManager.instance.appScope.launch {
                                DataManager.instance.moveWebAppsToGroup(
                                    listOf(webapp.uuid),
                                    targetGroupUuid,
                                )
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

        private fun openSettings(webapp: WebApp) {
            val intent = Intent(activity, WebAppSettingsActivity::class.java)
            intent.putExtra(Const.INTENT_WEBAPP_UUID, webapp.uuid)
            (activity as? MainActivity)?.launchSettings(intent)
                ?: activity.startActivity(intent)
        }

        private fun cloneWebApp(webapp: WebApp) {
            val clonedWebApp = WebApp(webapp.baseUrl)
            clonedWebApp.title = webapp.title
            clonedWebApp.settings = webapp.settings.deepCopy()
            clonedWebApp.order = webapp.order + 1
            clonedWebApp.groupUuid = webapp.groupUuid
            val copyIcon = webapp.hasCustomIcon

            DataManager.instance.appScope.launch {
                if (copyIcon) {
                    withContext(Dispatchers.IO) {
                        try {
                            val destFile = clonedWebApp.iconFile
                            destFile.parentFile?.mkdirs()
                            webapp.iconFile.copyTo(destFile, overwrite = true)
                        } catch (e: Exception) {
                            Log.w(TAG, "cloneWebApp: icon copy failed for ${webapp.uuid}", e)
                        }
                    }
                }
                DataManager.instance.addWebsite(clonedWebApp)
            }
        }

        private fun shareWebApp(webapp: WebApp) {
            val shareHost = requireNotNull(activity as? WebAppShareHost) {
                "shareWebApp requires the host activity to implement WebAppShareHost"
            }
            ShareSecretsDialog.confirmForWebApps(activity, listOf(webapp)) { includeSecrets ->
                shareHost.shareApps(listOf(webapp), includeSecrets)
            }
        }

        private fun deleteWebApp(webapp: WebApp) {
            val refreshHost = activity as? WebAppShareHost
            scheduleEntityDelete(
                activity = activity,
                uuids = listOf(webapp.uuid),
                message = activity.getString(R.string.x_was_removed, webapp.title),
                pendingDeleteSet = DataManager.instance.pendingDeleteWebAppUuids,
                onPendingChanged = { refreshHost?.refreshWebAppList() },
                commitDelete = { uuids -> DataManager.instance.deleteWebApps(uuids, activity) },
            )
        }

        companion object {
            private const val TAG = "WebAppListAdapter"
            private const val MENU_GROUP_BASE = 10000
        }
    }
}
