package wtf.mazy.peel.ui.entitylist

import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager

data class SelectionConfig(
    @get:StringRes val titleResForCount: Int,
    @get:MenuRes val selectionMenuRes: Int? = null,
    @get:IdRes val moveActionId: Int? = null,
    @get:IdRes val deleteActionId: Int? = null,
    @get:DrawableRes val activeFabIcon: Int = R.drawable.ic_baseline_share_24,
    @get:DrawableRes val idleFabIcon: Int = R.drawable.ic_add_24dp,
)

class EntitySelectionController<T : Any>(
    private val host: EntityListHost,
    private val actions: EntitySelectionActions<T>,
    private val resolveItems: (Set<String>) -> List<T>,
    private val onChanged: () -> Unit,
    private val isSearchActive: () -> Boolean = { false },
    private val config: SelectionConfig,
) {

    var isActive: Boolean = false
        private set

    val selectedIds: Set<String> get() = selectedUuids.toSet()

    private val selectedUuids = mutableSetOf<String>()

    fun isSelected(uuid: String) = uuid in selectedUuids

    fun enter(uuid: String) {
        if (isActive) {
            toggle(uuid)
            return
        }
        isActive = true
        selectedUuids.clear()
        selectedUuids.add(uuid)
        host.updateBackPressEnabled()
        if (!isSearchActive()) applySelectionToolbar()
        host.animateFabSwap(config.activeFabIcon)
        onChanged()
    }

    fun toggle(uuid: String) {
        if (uuid in selectedUuids) selectedUuids.remove(uuid) else selectedUuids.add(uuid)
        if (selectedUuids.isEmpty()) {
            exit()
            return
        }
        if (!isSearchActive()) {
            host.toolbar.title =
                host.hostActivity.getString(config.titleResForCount, selectedUuids.size)
        }
        onChanged()
    }

    fun exit() {
        if (!isActive) return
        isActive = false
        selectedUuids.clear()
        host.updateBackPressEnabled()
        if (isSearchActive()) {
            host.fab.hide()
        } else {
            host.applyNormalToolbar()
            host.animateFabSwap(config.idleFabIcon)
        }
        onChanged()
    }

    fun reapplyToolbar() {
        applySelectionToolbar()
    }

    fun performShare() {
        if (selectedUuids.isEmpty()) return
        val items = resolveItems(selectedUuids)
        if (items.isEmpty()) return
        actions.confirmShare(items) { includeSecrets ->
            exit()
            actions.share(items, includeSecrets)
        }
    }

    fun onMenuItemClicked(item: MenuItem): Boolean {
        return when (item.itemId) {
            config.moveActionId -> {
                showMovePopup(); true
            }

            config.deleteActionId -> {
                confirmDelete(); true
            }

            else -> false
        }
    }

    private fun applySelectionToolbar() {
        host.crossfadeToolbar {
            host.removeSearchViewFromToolbar()
            host.toolbar.menu.clear()
            config.selectionMenuRes?.let {
                host.hostActivity.menuInflater.inflate(it, host.toolbar.menu)
            }
            config.moveActionId?.let { id ->
                host.toolbar.menu.findItem(id)?.isVisible = actions.moveTargets.isNotEmpty()
            }
            host.toolbar.setOnMenuItemClickListener { onMenuItemClicked(it) }
            host.toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            host.toolbar.setNavigationOnClickListener { exit() }
            host.toolbar.title =
                host.hostActivity.getString(config.titleResForCount, selectedUuids.size)
        }
    }

    private fun showMovePopup() {
        if (selectedUuids.isEmpty()) return
        val targets = actions.moveTargets
        if (targets.isEmpty()) return
        val anchor = config.moveActionId
            ?.let { host.toolbar.findViewById<View>(it) }
            ?: host.toolbar
        val popup = PopupMenu(host.hostActivity, anchor)
        targets.forEachIndexed { index, target ->
            popup.menu.add(0, MENU_MOVE_BASE + index, index, target.title)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            val idx = menuItem.itemId - MENU_MOVE_BASE
            if (idx in targets.indices) {
                val target = targets[idx]
                val uuids = selectedUuids.toList()
                exit()
                DataManager.instance.appScope.launch {
                    actions.commitMove(uuids, target.groupUuid)
                }
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun confirmDelete() {
        if (selectedUuids.isEmpty()) return
        val activity = host.hostActivity
        MaterialAlertDialogBuilder(activity)
            .setTitle(actions.deleteTitle())
            .setMessage(actions.deleteMessage(selectedUuids.size))
            .setPositiveButton(R.string.delete) { _, _ -> performDelete() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDelete() {
        val uuids = selectedUuids.toList()
        val count = uuids.size
        exit()
        scheduleEntityDelete(
            activity = host.hostActivity,
            uuids = uuids,
            message = actions.deletedToast(count),
            pendingDeleteSet = actions.pendingDeleteSet,
            onPendingChanged = onChanged,
            commitDelete = actions::commitDelete,
        )
    }

    companion object {
        private const val MENU_MOVE_BASE = 20000
    }
}
