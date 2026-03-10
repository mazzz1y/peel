package wtf.mazy.peel.ui.webapplist

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.WebAppSettingsActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.WebViewLauncher.startWebView
import wtf.mazy.peel.util.displayUrl
import java.util.Collections
import androidx.core.view.isVisible

class WebAppListAdapter(
    private val activityOfFragment: Activity,
) : RecyclerView.Adapter<WebAppListAdapter.ViewHolder>() {

    var items: MutableList<WebApp> = mutableListOf()
        private set

    var groupFilter: String? = null
    var searchQuery: String = ""
    var showGroupLabels: Boolean = false

    private val selectionHost = activityOfFragment as? SelectionModeHost

    private val checkIconColor by lazy {
        MaterialColors.getColor(
            activityOfFragment.window.decorView,
            androidx.appcompat.R.attr.colorPrimary,
            0,
        )
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val titleView: TextView = itemView.findViewById(R.id.btnWebAppTitle)
        val urlView: TextView = itemView.findViewById(R.id.appUrl)
        val groupLabel: TextView = itemView.findViewById(R.id.groupLabel)
        val menuButton: ImageView = itemView.findViewById(R.id.btnMenu)
        val iconSandbox: ImageView = itemView.findViewById(R.id.iconSandbox)
        val iconEphemeral: ImageView = itemView.findViewById(R.id.iconEphemeral)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.web_app_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.titleView.text = item.title
        holder.urlView.text = displayUrl(item.baseUrl)

        if (showGroupLabels) {
            val groupName = item.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
            holder.groupLabel.text = groupName
            holder.groupLabel.visibility = if (groupName != null) View.VISIBLE else View.GONE
        } else {
            holder.groupLabel.visibility = View.GONE
        }

        holder.iconSandbox.visibility = if (item.isUseContainer) View.VISIBLE else View.GONE
        holder.iconEphemeral.visibility =
            if (item.isUseContainer && item.isEphemeralSandbox) View.VISIBLE else View.GONE

        val selected = selectionHost?.isSelected(item.uuid) == true
        applyIconState(holder.appIcon, item, selected)
        applyModeState(holder, item)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val item = items[position]

        if (PAYLOAD_SELECTION_TOGGLE in payloads) {
            val selected = selectionHost?.isSelected(item.uuid) == true
            animateIconSwap(holder.appIcon, item, selected)
            animateModeTransition(holder, item)
            return
        }

        if (PAYLOAD_MODE_CHANGE in payloads) {
            animateModeTransition(holder, item)
            return
        }

        super.onBindViewHolder(holder, position, payloads)
    }

    private fun applyModeState(holder: ViewHolder, item: WebApp) {
        holder.menuButton.animate().cancel()
        holder.iconSandbox.animate().cancel()
        holder.iconEphemeral.animate().cancel()

        if (selectionHost?.isInSelectionMode == true) {
            holder.menuButton.alpha = 0f
            holder.itemView.post {
                visibleBadgeIcons(holder).forEach {
                    it.translationX = badgeSlideDistance(holder.menuButton, it)
                }
            }
            applySelectionListeners(holder, item)
        } else {
            holder.menuButton.alpha = 1f
            holder.iconSandbox.translationX = 0f
            holder.iconEphemeral.translationX = 0f
            applyNormalListeners(holder, item)
        }
    }

    private fun animateModeTransition(holder: ViewHolder, item: WebApp) {
        if (selectionHost?.isInSelectionMode == true) {
            holder.menuButton.animate().alpha(0f).setDuration(Const.ANIM_DURATION_MEDIUM).start()
            visibleBadgeIcons(holder).forEach {
                it.animate()
                    .translationX(badgeSlideDistance(holder.menuButton, it))
                    .setDuration(Const.ANIM_DURATION_MEDIUM)
                    .start()
            }
            applySelectionListeners(holder, item)
        } else {
            holder.menuButton.animate().alpha(1f).setDuration(Const.ANIM_DURATION_MEDIUM).start()
            listOf(holder.iconSandbox, holder.iconEphemeral).forEach {
                it.animate().translationX(0f).setDuration(Const.ANIM_DURATION_MEDIUM).start()
            }
            applyNormalListeners(holder, item)
        }
    }

    private fun applySelectionListeners(holder: ViewHolder, item: WebApp) {
        val host = selectionHost ?: return
        holder.itemView.setOnClickListener { host.toggleSelection(item.uuid) }
        holder.appIcon.setOnClickListener { host.toggleSelection(item.uuid) }
        holder.menuButton.setOnClickListener(null)
        holder.menuButton.isClickable = false
        holder.menuButton.isEnabled = false
    }

    private fun applyNormalListeners(holder: ViewHolder, item: WebApp) {
        holder.menuButton.isEnabled = true
        holder.itemView.setOnClickListener { startWebView(item, activityOfFragment) }
        holder.appIcon.setOnClickListener {
            selectionHost?.enterSelectionMode(item.uuid)
        }
        holder.menuButton.setOnClickListener { view ->
            showPopupMenu(view, item, holder.bindingAdapterPosition)
        }
    }

    private fun visibleBadgeIcons(holder: ViewHolder): List<ImageView> =
        listOf(holder.iconSandbox, holder.iconEphemeral).filter { it.isVisible }

    private fun badgeSlideDistance(menuButton: View, badgeIcon: View): Float =
        (menuButton.width + badgeIcon.width) / 2f

    private fun animateIconSwap(icon: ImageView, item: WebApp, selected: Boolean) {
        icon.animate().cancel()
        icon.animate()
            .scaleX(0f)
            .scaleY(0f)
            .setDuration(Const.ANIM_DURATION_FAST)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                applyIconState(icon, item, selected)
                icon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Const.ANIM_DURATION_FAST)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun applyIconState(icon: ImageView, item: WebApp, selected: Boolean) {
        if (selected) {
            icon.background = null
            icon.setImageResource(R.drawable.ic_check_24)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(checkIconColor)
        } else {
            icon.imageTintList = null
            icon.background = null
            icon.setImageBitmap(item.resolveIcon())
        }
    }

    override fun getItemCount() = items.size

    fun moveItem(from: Int, to: Int) {
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    private fun showPopupMenu(view: View, webapp: WebApp, position: Int) {
        val popup = PopupMenu(activityOfFragment, view)
        popup.menuInflater.inflate(R.menu.webapp_item_menu, popup.menu)

        val groups = DataManager.instance.sortedGroups
        if (groups.isNotEmpty()) {
            val subMenu =
                popup.menu.addSubMenu(
                    0, 0, 20, activityOfFragment.getString(R.string.move_to_group)
                )
            groups.forEachIndexed { index, group ->
                subMenu.add(0, MENU_GROUP_BASE + index, index, group.title)
            }
            subMenu.add(
                0,
                MENU_GROUP_BASE + groups.size,
                groups.size,
                activityOfFragment.getString(R.string.none),
            )
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    openSettings(webapp)
                    true
                }

                R.id.action_add_to_home -> {
                    ShortcutHelper.createShortcut(webapp, activityOfFragment)
                    true
                }

                R.id.action_clone -> {
                    cloneWebApp(webapp, position)
                    true
                }

                R.id.action_delete -> {
                    deleteWebApp(webapp, position)
                    true
                }

                else -> {
                    val groupIndex = menuItem.itemId - MENU_GROUP_BASE
                    if (groupIndex in 0..groups.size) {
                        webapp.groupUuid =
                            if (groupIndex < groups.size) groups[groupIndex].uuid else null
                        DataManager.instance.replaceWebApp(webapp)
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
        val intent = Intent(activityOfFragment, WebAppSettingsActivity::class.java)
        intent.putExtra(Const.INTENT_WEBAPP_UUID, webapp.uuid)
        activityOfFragment.startActivity(intent)
    }

    private fun cloneWebApp(webapp: WebApp, position: Int) {
        val clonedWebApp = WebApp(webapp.baseUrl)
        clonedWebApp.title = webapp.title
        clonedWebApp.settings = webapp.settings.deepCopy()
        clonedWebApp.order = webapp.order + 1
        clonedWebApp.groupUuid = webapp.groupUuid

        if (webapp.hasCustomIcon) {
            try {
                val destFile = clonedWebApp.iconFile
                destFile.parentFile?.mkdirs()
                webapp.iconFile.copyTo(destFile, overwrite = true)
            } catch (_: Exception) {
            }
        }

        DataManager.instance.addWebsite(clonedWebApp)
        val insertPosition = position + 1
        items.add(insertPosition, clonedWebApp)
        notifyItemInserted(insertPosition)
    }

    private fun deleteWebApp(webapp: WebApp, position: Int) {
        if (position < 0 || position >= items.size) return

        webapp.markInactiveOnly()
        items.removeAt(position)
        notifyItemRemoved(position)

        NotificationUtils.showUndoSnackBar(
            activity = activityOfFragment,
            message = activityOfFragment.getString(R.string.x_was_removed, webapp.title),
            onUndo = {
                webapp.isActiveEntry = true
                val insertPosition = minOf(position, items.size)
                items.add(insertPosition, webapp)
                notifyItemInserted(insertPosition)
            },
            onCommit = {
                webapp.deleteShortcuts(activityOfFragment)
                webapp.cleanupWebAppData(activityOfFragment)
                DataManager.instance.removeWebApp(webapp)
            },
        )
    }

    fun updateWebAppList() {
        var newItems =
            when (groupFilter) {
                null -> DataManager.instance.activeWebsites.toMutableList()
                WebAppListFragment.UNGROUPED_FILTER ->
                    DataManager.instance.activeWebsitesForGroup(null).toMutableList()
                else -> DataManager.instance.activeWebsitesForGroup(groupFilter).toMutableList()
            }
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            val groupNames = DataManager.instance.sortedGroups.associate { it.uuid to it.title }
            newItems =
                newItems
                    .filter { app ->
                        app.title.lowercase().contains(query) ||
                            app.baseUrl.lowercase().contains(query) ||
                            groupNames[app.groupUuid]?.lowercase()?.contains(query) == true
                    }
                    .toMutableList()
        }
        val diff = DiffUtil.calculateDiff(WebAppDiffCallback(items, newItems))
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    private class WebAppDiffCallback(
        private val oldList: List<WebApp>,
        private val newList: List<WebApp>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            oldList[oldPos].uuid == newList[newPos].uuid
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            oldList[oldPos].uuid == newList[newPos].uuid
    }

    companion object {
        private const val MENU_GROUP_BASE = 10000
        const val PAYLOAD_SELECTION_TOGGLE = "selection_toggle"
        const val PAYLOAD_MODE_CHANGE = "mode_change"
    }
}
