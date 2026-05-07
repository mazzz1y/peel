package wtf.mazy.peel.activities

import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.ApplyTiming
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.dialog.showSandboxInputDialog
import wtf.mazy.peel.ui.entitylist.EntityListActivity
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.entitylist.EntitySelectionController
import wtf.mazy.peel.ui.entitylist.SelectionConfig
import wtf.mazy.peel.ui.entitylist.scheduleEntityDelete
import wtf.mazy.peel.ui.grouplist.GroupListAdapter
import wtf.mazy.peel.ui.grouplist.GroupSelectionActions
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.restartApp
import wtf.mazy.peel.util.withBoldSpan

class GroupListActivity : EntityListActivity<WebAppGroup>() {

    override val titleRes: Int = R.string.groups
    override val emptyStateRes: Int = R.string.groups_empty_state
    override val supportsDrag: Boolean = true

    private val transferLoader: LoadingDialogController by lazy { LoadingDialogController(this) }
    private val selectionActions: GroupSelectionActions by lazy {
        GroupSelectionActions(this, transferLoader)
    }
    private lateinit var selectionController: EntitySelectionController<WebAppGroup>

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val timingName = result.data?.getStringExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING)
                ?: return@registerForActivityResult
            val timing = ApplyTiming.valueOf(timingName)
            ApplyTimingRegistry.showSnackbarForTiming(
                timing,
                findViewById(android.R.id.content),
            ) { restartApp(this) }
        }

    override fun createAdapter(): EntityListAdapter<WebAppGroup, *> {
        selectionController = EntitySelectionController(
            host = this,
            actions = selectionActions,
            resolveItems = { ids ->
                DataManager.instance.getGroups().filter { it.uuid in ids }
            },
            onChanged = {
                refreshList()
                setDragEnabled(!selectionController.isActive)
            },
            config = SelectionConfig(
                titleResForCount = R.string.n_groups_selected,
                selectionMenuRes = R.menu.menu_selection_group,
                deleteActionId = R.id.action_delete_selected,
            ),
        )
        return GroupListAdapter(GroupItemActions(), checkIconColor)
    }

    override fun loadEntities(): List<WebAppGroup> {
        val pending = DataManager.instance.pendingDeleteGroupUuids
        return DataManager.instance.sortedGroups.filterNot { it.uuid in pending }
    }

    override fun buildRow(entity: WebAppGroup): EntityRow<WebAppGroup> = EntityRow(
        entity = entity,
        selected = selectionController.isSelected(entity.uuid),
        inSelectionMode = selectionController.isActive,
    )

    override fun rowEntityUuid(entity: WebAppGroup): String = entity.uuid

    override fun shouldHandleBackPress(): Boolean = selectionController.isActive

    override fun handleBackPress() {
        if (selectionController.isActive) selectionController.exit() else finish()
    }

    override fun onAddClicked() {
        showSandboxInputDialog(
            titleRes = R.string.add_group,
            hintRes = R.string.group_name_hint,
        ) { result ->
            val group =
                WebAppGroup(title = result.text, order = DataManager.instance.getGroups().size)
            group.isUseContainer = result.sandbox
            group.isEphemeralSandbox = result.ephemeral
            DataManager.instance.appScope.launch {
                DataManager.instance.addGroup(group)
            }
        }
    }

    override fun onFabClicked() {
        if (selectionController.isActive) selectionController.performShare() else onAddClicked()
    }

    override suspend fun reorder(uuids: List<String>) {
        DataManager.instance.reorderGroups(uuids)
    }

    override fun onDestroy() {
        transferLoader.dismiss()
        super.onDestroy()
    }

    private fun showDeleteGroupDialog(group: WebAppGroup) {
        val appsInGroup = DataManager.instance.activeWebsitesForGroup(group.uuid)
        if (appsInGroup.isEmpty()) {
            scheduleGroupDelete(group, ungroupApps = false)
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_group, null)
        val message = dialogView.findViewById<TextView>(R.id.delete_group_message)
        val switchUngroup = dialogView.findViewById<MaterialSwitch>(R.id.switchUngroupApps)

        message.text =
            getString(R.string.delete_group_confirm, group.title).withBoldSpan(group.title)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_group_title))
            .setView(dialogView)
            .setPositiveButton(R.string.delete) { _, _ ->
                scheduleGroupDelete(group, ungroupApps = switchUngroup.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scheduleGroupDelete(group: WebAppGroup, ungroupApps: Boolean) {
        scheduleEntityDelete(
            activity = this,
            uuids = listOf(group.uuid),
            message = getString(R.string.x_was_removed, group.title),
            pendingDeleteSet = DataManager.instance.pendingDeleteGroupUuids,
            onPendingChanged = ::refreshList,
            commitDelete = { uuids ->
                uuids.forEach { uuid ->
                    val target = DataManager.instance.getGroup(uuid) ?: return@forEach
                    if (!ungroupApps) {
                        DataManager.instance.activeWebsitesForGroup(uuid).forEach { app ->
                            DataManager.instance.cleanupAndRemoveWebApp(
                                app.uuid,
                                this@GroupListActivity,
                            )
                        }
                    }
                    DataManager.instance.removeGroup(target, ungroupApps = ungroupApps)
                }
            },
        )
    }

    private inner class GroupItemActions : EntityRowActions<WebAppGroup> {
        override fun onItemClick(item: WebAppGroup) {
            val intent = Intent(this@GroupListActivity, GroupSettingsActivity::class.java)
            intent.putExtra(Const.INTENT_GROUP_UUID, item.uuid)
            settingsLauncher.launch(intent)
        }

        override fun onItemIconClick(item: WebAppGroup) {
            selectionController.enter(item.uuid)
        }

        override fun onItemMenu(view: View, item: WebAppGroup) {
            val popup = PopupMenu(this@GroupListActivity, view)
            popup.menuInflater.inflate(R.menu.group_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_share_group -> {
                        selectionActions.confirmShare(listOf(item)) { includeSecrets ->
                            selectionActions.share(listOf(item), includeSecrets)
                        }
                        true
                    }

                    R.id.action_create_shortcut -> {
                        ShortcutHelper.createShortcut(item, this@GroupListActivity); true
                    }

                    R.id.action_edit -> {
                        val intent =
                            Intent(this@GroupListActivity, GroupSettingsActivity::class.java)
                        intent.putExtra(Const.INTENT_GROUP_UUID, item.uuid)
                        settingsLauncher.launch(intent); true
                    }

                    R.id.action_delete -> {
                        showDeleteGroupDialog(item); true
                    }

                    else -> false
                }
            }
            popup.show()
        }
    }
}
