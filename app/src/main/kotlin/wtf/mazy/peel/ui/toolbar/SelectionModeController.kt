package wtf.mazy.peel.ui.toolbar

import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.util.NotificationUtils

class SelectionModeController(
    private val host: ToolbarModeHost,
    private val isSearchActive: () -> Boolean,
) {

    val isActive: Boolean get() = _selectionMode

    private var _selectionMode = false
    private val selectedUuids = mutableSetOf<String>()
    private val pendingExitRunnable = Runnable { exit() }

    fun isSelected(uuid: String) = uuid in selectedUuids

    fun enter(uuid: String) {
        host.fab.removeCallbacks(pendingExitRunnable)
        if (_selectionMode) {
            toggle(uuid)
            return
        }
        _selectionMode = true
        selectedUuids.clear()
        selectedUuids.add(uuid)
        host.updateBackPressEnabled()

        if (!isSearchActive()) {
            applySelectionToolbar()
        }
        host.animateFabSwap(R.drawable.ic_baseline_share_24)
        host.dispatchSelectionEntered(uuid)
    }

    fun toggle(uuid: String) {
        if (uuid in selectedUuids) selectedUuids.remove(uuid) else selectedUuids.add(uuid)
        if (selectedUuids.isEmpty()) {
            host.dispatchSelectionToggled(uuid)
            host.fab.postDelayed(pendingExitRunnable, EXIT_DELAY_MS)
            return
        }
        if (!isSearchActive()) {
            host.toolbar.title =
                host.hostActivity.getString(R.string.n_apps_selected, selectedUuids.size)
        }
        host.dispatchSelectionToggled(uuid)
    }

    fun exit() {
        host.fab.removeCallbacks(pendingExitRunnable)
        val previouslySelected = selectedUuids.toSet()
        _selectionMode = false
        selectedUuids.clear()
        host.updateBackPressEnabled()

        if (isSearchActive()) {
            host.fab.hide()
        } else {
            host.applyNormalToolbar()
            host.animateFabSwap(R.drawable.ic_add_24dp)
        }
        host.dispatchSelectionExited(previouslySelected)
    }

    fun reapplyToolbar() {
        applySelectionToolbar()
    }

    fun onFabClicked() {
        performShareSelected()
    }

    fun onMenuItemClicked(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_move_selected -> { showMoveSelectedPopup(); true }
            R.id.action_delete_selected -> { confirmDeleteSelected(); true }
            else -> false
        }
    }

    private fun applySelectionToolbar() {
        host.crossfadeToolbar {
            host.removeSearchViewFromToolbar()
            host.toolbar.menu.clear()
            host.hostActivity.menuInflater.inflate(R.menu.menu_selection, host.toolbar.menu)
            host.toolbar.menu.findItem(R.id.action_move_selected)?.isVisible =
                DataManager.instance.sortedGroups.isNotEmpty()
            host.toolbar.setOnMenuItemClickListener { onMenuItemClicked(it) }
            host.toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            host.toolbar.setNavigationOnClickListener { exit() }
            host.toolbar.title =
                host.hostActivity.getString(R.string.n_apps_selected, selectedUuids.size)
        }
    }

    private fun performShareSelected() {
        val activity = host.hostActivity
        if (selectedUuids.isEmpty()) {
            NotificationUtils.showToast(activity, activity.getString(R.string.share_no_selection), Toast.LENGTH_SHORT)
            return
        }

        val webApps = resolveSelectedWebApps()
        if (webApps.isEmpty()) {
            NotificationUtils.showToast(activity, activity.getString(R.string.share_no_selection), Toast.LENGTH_SHORT)
            return
        }

        if (containsSecrets(webApps)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_secrets_title)
                .setMessage(R.string.share_secrets_description)
                .setPositiveButton(R.string.share_secrets_exclude) { _, _ ->
                    exit()
                    host.shareApps(webApps, includeSecrets = false)
                }
                .setNegativeButton(R.string.share_secrets_include) { _, _ ->
                    exit()
                    host.shareApps(webApps, includeSecrets = true)
                }
                .show()
        } else {
            exit()
            host.shareApps(webApps, includeSecrets = true)
        }
    }

    private fun containsSecrets(webApps: List<WebApp>): Boolean {
        return webApps.any { app ->
            val settings = app.settings
            settings.customHeaders?.any { (k, v) -> k.isNotBlank() || v.isNotBlank() } == true
                || !settings.basicAuthUsername.isNullOrBlank()
                || !settings.basicAuthPassword.isNullOrBlank()
        }
    }

    private fun showMoveSelectedPopup() {
        if (selectedUuids.isEmpty()) return

        val anchor = host.toolbar.findViewById<View>(R.id.action_move_selected) ?: host.toolbar
        val popup = PopupMenu(host.hostActivity, anchor)

        val groups = DataManager.instance.sortedGroups
        groups.forEachIndexed { index, group ->
            popup.menu.add(0, MENU_MOVE_GROUP_BASE + index, index, group.title)
        }
        popup.menu.add(
            0,
            MENU_MOVE_GROUP_BASE + groups.size,
            groups.size,
            host.hostActivity.getString(R.string.ungrouped),
        )

        popup.setOnMenuItemClickListener { menuItem ->
            val groupIndex = menuItem.itemId - MENU_MOVE_GROUP_BASE
            if (groupIndex in 0..groups.size) {
                val targetGroupUuid =
                    if (groupIndex < groups.size) groups[groupIndex].uuid else null
                performMoveSelected(targetGroupUuid)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun performMoveSelected(targetGroupUuid: String?) {
        val webApps = resolveSelectedWebApps()
        webApps.forEach { webapp ->
            webapp.groupUuid = targetGroupUuid
            DataManager.instance.replaceWebApp(webapp)
        }
        exit()
    }

    private fun confirmDeleteSelected() {
        if (selectedUuids.isEmpty()) return
        val activity = host.hostActivity

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.remove_apps_title)
            .setMessage(activity.getString(R.string.remove_apps_confirm, selectedUuids.size))
            .setPositiveButton(R.string.delete) { _, _ -> performDeleteSelected() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDeleteSelected() {
        val activity = host.hostActivity
        val webApps = resolveSelectedWebApps()
        val count = webApps.size

        webApps.forEach { it.markInactiveOnly() }
        exit()
        host.refreshCurrentPages()

        NotificationUtils.showUndoSnackBar(
            activity = activity,
            message = activity.getString(R.string.n_apps_removed, count),
            onUndo = {
                webApps.forEach { it.isActiveEntry = true }
                host.refreshCurrentPages()
            },
            onCommit = {
                webApps.forEach { webapp ->
                    webapp.deleteShortcuts(activity)
                    webapp.cleanupWebAppData(activity)
                    DataManager.instance.removeWebApp(webapp)
                }
            },
        )
    }

    private fun resolveSelectedWebApps(): List<WebApp> {
        val byUuid = DataManager.instance.getWebsites().associateBy { it.uuid }
        return selectedUuids.mapNotNull(byUuid::get)
    }

    companion object {
        private const val MENU_MOVE_GROUP_BASE = 20000
        private const val EXIT_DELAY_MS = 200L
    }
}
