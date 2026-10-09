package wtf.mazy.peel.ui.entitylist

import android.view.MenuItem
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.MenuRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R

data class SelectionConfig(
    @get:PluralsRes val titleResForCount: Int,
    @get:MenuRes val selectionMenuRes: Int? = null,
    @get:IdRes val moveActionId: Int? = null,
    @get:IdRes val deleteActionId: Int? = null,
    @get:DrawableRes val activeFabIcon: Int = R.drawable.ic_symbols_share_24,
    @get:StringRes val activeFabDescription: Int = R.string.share,
)

class EntitySelectionController<T : Any>(
    private val activity: AppCompatActivity,
    private val toolbar: MaterialToolbar,
    private val actions: EntitySelectionHandler<T>,
    private val resolveItems: (Set<String>) -> List<T>,
    private val onChanged: () -> Unit,
    val config: SelectionConfig,
) {

    val isActive: Boolean get() = selectedUuids.isNotEmpty()

    val selectedIds: Set<String> get() = selectedUuids.toSet()

    val count: Int get() = selectedUuids.size

    val hasMoveTargets: Boolean get() = actions.moveTargets.isNotEmpty()

    private val selectedUuids = mutableSetOf<String>()

    fun isSelected(uuid: String) = uuid in selectedUuids

    fun enter(uuid: String) {
        if (isActive) {
            toggle(uuid)
            return
        }
        selectedUuids.add(uuid)
        onChanged()
    }

    fun toggle(uuid: String) {
        if (uuid in selectedUuids) selectedUuids.remove(uuid) else selectedUuids.add(uuid)
        onChanged()
    }

    fun exit() {
        if (!isActive) return
        selectedUuids.clear()
        onChanged()
    }

    fun restore(uuids: Collection<String>) {
        selectedUuids.clear()
        selectedUuids.addAll(uuids)
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

    private fun showMovePopup() {
        if (selectedUuids.isEmpty()) return
        val targets = actions.moveTargets
        if (targets.isEmpty()) return
        val anchor = config.moveActionId
            ?.let { toolbar.findViewById<View>(it) }
            ?: toolbar
        val popup = PopupMenu(activity, anchor)
        targets.forEachIndexed { index, target ->
            popup.menu.add(0, MENU_MOVE_BASE + index, index, target.title)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            val idx = menuItem.itemId - MENU_MOVE_BASE
            if (idx in targets.indices) {
                val target = targets[idx]
                val uuids = selectedUuids.toList()
                exit()
                activity.lifecycleScope.launch {
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
            activity = activity,
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
