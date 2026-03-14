package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.ShareSecretsDialog
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.dialog.showSandboxInputDialog
import wtf.mazy.peel.ui.dragReorderCallback
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils
import java.util.Collections

class GroupListActivity : AppCompatActivity() {

    private lateinit var adapter: GroupListAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fab: FloatingActionButton
    private lateinit var transferLoader: LoadingDialogController
    private var itemTouchHelper: ItemTouchHelper? = null
    private var isInSelectionMode = false
    private val selectedGroupUuids = mutableSetOf<String>()
    private val checkIconColor by lazy {
        MaterialColors.getColor(window.decorView, androidx.appcompat.R.attr.colorPrimary, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_list)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.groups)

        onBackPressedDispatcher.addCallback(this) {
            if (isInSelectionMode) exitSelectionMode() else finish()
        }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = GroupListAdapter()
        list = findViewById(R.id.group_list)
        emptyStateText = findViewById(R.id.empty_state_text)
        transferLoader = LoadingDialogController(this)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper?.attachToRecyclerView(list)

        fab = findViewById(R.id.fab_add_group)
        fab.setOnClickListener {
            if (isInSelectionMode) {
                shareSelectedGroups()
            } else {
                showAddGroupDialog()
            }
        }

        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fab.requestLayout()
        }

        updateList()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.instance.state.collect {
                    if (selectedGroupUuids.isNotEmpty()) {
                        val valid =
                            DataManager.instance.getGroups().mapTo(mutableSetOf()) { it.uuid }
                        selectedGroupUuids.retainAll(valid)
                        if (isInSelectionMode && selectedGroupUuids.isEmpty()) exitSelectionMode()
                        if (isInSelectionMode) updateSelectionTitle()
                    }
                    updateList()
                }
            }
        }
    }

    override fun onDestroy() {
        transferLoader.dismiss()
        super.onDestroy()
    }

    private fun updateList() {
        adapter.updateList()
        if (adapter.items.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            list.visibility = View.GONE
        } else {
            emptyStateText.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
    }

    private fun showAddGroupDialog() {
        showSandboxInputDialog(
            titleRes = R.string.add_group,
            hintRes = R.string.group_name_hint,
        ) { result ->
            val group =
                WebAppGroup(title = result.text, order = DataManager.instance.getGroups().size)
            group.isUseContainer = result.sandbox
            group.isEphemeralSandbox = result.ephemeral
            lifecycleScope.launch {
                DataManager.instance.addGroup(group)
            }
        }
    }

    private fun showDeleteGroupDialog(group: WebAppGroup) {
        val appsInGroup = DataManager.instance.activeWebsitesForGroup(group.uuid)
        if (appsInGroup.isEmpty()) {
            lifecycleScope.launch {
                DataManager.instance.removeGroup(group, ungroupApps = false)
            }
            NotificationUtils.showUndoSnackBar(
                activity = this,
                message = getString(R.string.x_was_removed, group.title),
                onUndo = {
                    lifecycleScope.launch {
                        DataManager.instance.addGroup(group)
                    }
                },
                onCommit = {},
            )
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_group, null)
        val message = dialogView.findViewById<TextView>(R.id.delete_group_message)
        val switchUngroup = dialogView.findViewById<MaterialSwitch>(R.id.switchUngroupApps)

        message.text = getString(R.string.delete_group_confirm, group.title)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_group_title))
            .setView(dialogView)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    if (!switchUngroup.isChecked) {
                        appsInGroup.forEach {
                            DataManager.instance.cleanupAndRemoveWebApp(
                                it.uuid,
                                this@GroupListActivity
                            )
                        }
                    }
                    DataManager.instance.removeGroup(group, ungroupApps = switchUngroup.isChecked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareGroup(group: WebAppGroup) {
        val webApps = DataManager.instance.activeWebsitesForGroup(group.uuid)
        ShareSecretsDialog.confirmForGroupsAndApps(this, listOf(group), webApps) { includeSecrets ->
            buildAndLaunchGroupShare(listOf(group), webApps, includeSecrets)
        }
    }

    private fun buildAndLaunchGroupShare(
        groups: List<WebAppGroup>,
        webApps: List<WebApp>,
        includeSecrets: Boolean,
    ) {
        runWithLoader(
            activity = this,
            loader = transferLoader,
            showLoader = groups.size + webApps.size >= BackupManager.LOADER_THRESHOLD,
            loadingRes = R.string.preparing_export,
            ioTask = { BackupManager.buildGroupShareFile(groups, webApps, includeSecrets) },
        ) { file ->
            if (file == null || !BackupManager.launchShareChooser(this@GroupListActivity, file)) {
                NotificationUtils.showToast(
                    this@GroupListActivity,
                    getString(R.string.export_share_failed),
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    private fun enterSelectionMode(uuid: String) {
        if (isInSelectionMode) {
            toggleSelection(uuid)
            return
        }
        isInSelectionMode = true
        selectedGroupUuids.clear()
        selectedGroupUuids.add(uuid)
        toolbar.menu.clear()
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
        updateSelectionTitle()
        fab.setImageResource(R.drawable.ic_baseline_share_24)
        updateDragEnabled()
        adapter.animateEnterSelection(uuid)
    }

    private fun toggleSelection(uuid: String) {
        val previouslySelected = selectedGroupUuids.toSet()
        if (uuid in selectedGroupUuids) selectedGroupUuids.remove(uuid) else selectedGroupUuids.add(
            uuid
        )
        if (selectedGroupUuids.isEmpty()) {
            exitSelectionMode(previouslySelected)
            return
        }
        updateSelectionTitle()
        adapter.animateSelectionToggled(uuid)
    }

    private fun exitSelectionMode(previouslySelectedOverride: Set<String>? = null) {
        if (!isInSelectionMode) return
        val previouslySelected = previouslySelectedOverride ?: selectedGroupUuids.toSet()
        isInSelectionMode = false
        selectedGroupUuids.clear()
        supportActionBar?.title = getString(R.string.groups)
        fab.setImageResource(R.drawable.ic_add_24dp)
        updateDragEnabled()
        adapter.animateExitSelection(previouslySelected)
    }

    private fun updateSelectionTitle() {
        supportActionBar?.title = getString(R.string.n_groups_selected, selectedGroupUuids.size)
    }

    private fun updateDragEnabled() {
        if (isInSelectionMode) {
            itemTouchHelper?.attachToRecyclerView(null)
        } else {
            itemTouchHelper?.attachToRecyclerView(list)
        }
    }

    private fun shareSelectedGroups() {
        if (selectedGroupUuids.isEmpty()) {
            NotificationUtils.showToast(
                this,
                getString(R.string.share_no_selection),
                Toast.LENGTH_SHORT
            )
            return
        }
        val selectedGroups =
            DataManager.instance.getGroups().filter { it.uuid in selectedGroupUuids }
        val webApps =
            selectedGroups.flatMap { DataManager.instance.activeWebsitesForGroup(it.uuid) }
        ShareSecretsDialog.confirmForGroupsAndApps(
            this,
            selectedGroups,
            webApps
        ) { includeSecrets ->
            buildAndLaunchGroupShare(selectedGroups, webApps, includeSecrets)
        }
    }

    private val dragCallback =
        dragReorderCallback(
            onMove = { from, to -> adapter.moveItem(from, to) },
            onDrop = {
                lifecycleScope.launch {
                    DataManager.instance.reorderGroups(adapter.items.map { it.uuid })
                }
            },
        )

    inner class GroupListAdapter : RecyclerView.Adapter<GroupListAdapter.ViewHolder>() {
        var items: MutableList<WebAppGroup> = mutableListOf()
            private set

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconView: ImageView = itemView.findViewById(R.id.groupIcon)
            val titleView: TextView = itemView.findViewById(R.id.groupTitle)
            val menuButton: ImageView = itemView.findViewById(R.id.btnMenu)
            val iconSandbox: ImageView = itemView.findViewById(R.id.iconSandbox)
            val iconEphemeral: ImageView = itemView.findViewById(R.id.iconEphemeral)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.group_list_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = items[position]
            holder.titleView.text = group.title
            val selected = group.uuid in selectedGroupUuids
            applyIconState(holder.iconView, group, selected)
            applyModeState(holder, group)

            holder.iconSandbox.animate().cancel()
            holder.iconEphemeral.animate().cancel()
            holder.menuButton.animate().cancel()

            if (!isInSelectionMode) {
                holder.menuButton.alpha = 1f
                holder.iconSandbox.translationX = 0f
                holder.iconEphemeral.translationX = 0f
            }

            holder.iconSandbox.visibility = if (group.isUseContainer) View.VISIBLE else View.GONE
            holder.iconEphemeral.visibility =
                if (group.isUseContainer && group.isEphemeralSandbox) View.VISIBLE else View.GONE
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
            payloads: MutableList<Any>,
        ) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(holder, position, payloads)
                return
            }

            val group = items[position]

            if (PAYLOAD_SELECTION_TOGGLE in payloads) {
                val selected = group.uuid in selectedGroupUuids
                animateIconSwap(holder.iconView, group, selected)
                animateModeTransition(holder, group)
                return
            }

            if (PAYLOAD_MODE_CHANGE in payloads) {
                animateModeTransition(holder, group)
                return
            }

            super.onBindViewHolder(holder, position, payloads)
        }

        private fun applyModeState(holder: ViewHolder, group: WebAppGroup) {
            if (isInSelectionMode) {
                holder.menuButton.alpha = 0f
                holder.itemView.post {
                    visibleBadgeIcons(holder).forEach {
                        it.translationX = badgeSlideDistance(holder.menuButton, it)
                    }
                }
                applySelectionListeners(holder, group)
            } else {
                holder.menuButton.alpha = 1f
                holder.iconSandbox.translationX = 0f
                holder.iconEphemeral.translationX = 0f
                applyNormalListeners(holder, group)
            }
        }

        private fun animateModeTransition(holder: ViewHolder, group: WebAppGroup) {
            if (isInSelectionMode) {
                holder.menuButton.animate().alpha(0f).setDuration(Const.ANIM_DURATION_MEDIUM)
                    .start()
                visibleBadgeIcons(holder).forEach {
                    it.animate()
                        .translationX(badgeSlideDistance(holder.menuButton, it))
                        .setDuration(Const.ANIM_DURATION_MEDIUM)
                        .start()
                }
                applySelectionListeners(holder, group)
            } else {
                holder.menuButton.animate().alpha(1f).setDuration(Const.ANIM_DURATION_MEDIUM)
                    .start()
                listOf(holder.iconSandbox, holder.iconEphemeral).forEach {
                    it.animate().translationX(0f).setDuration(Const.ANIM_DURATION_MEDIUM).start()
                }
                applyNormalListeners(holder, group)
            }
        }

        private fun applySelectionListeners(holder: ViewHolder, group: WebAppGroup) {
            holder.itemView.setOnClickListener { toggleSelection(group.uuid) }
            holder.iconView.setOnClickListener { toggleSelection(group.uuid) }
            holder.menuButton.setOnClickListener(null)
            holder.menuButton.isClickable = false
            holder.menuButton.isEnabled = false
        }

        private fun applyNormalListeners(holder: ViewHolder, group: WebAppGroup) {
            holder.menuButton.isEnabled = true
            holder.itemView.setOnClickListener {
                val intent = Intent(this@GroupListActivity, GroupSettingsActivity::class.java)
                intent.putExtra(Const.INTENT_GROUP_UUID, group.uuid)
                startActivity(intent)
            }
            holder.iconView.setOnClickListener { enterSelectionMode(group.uuid) }
            holder.menuButton.setOnClickListener { view -> showPopupMenu(view, group) }
        }

        private fun visibleBadgeIcons(holder: ViewHolder): List<ImageView> =
            listOf(
                holder.iconSandbox,
                holder.iconEphemeral
            ).filter { it.visibility == View.VISIBLE }

        private fun badgeSlideDistance(menuButton: View, badgeIcon: View): Float =
            (menuButton.width + badgeIcon.width) / 2f

        private fun animateIconSwap(icon: ImageView, group: WebAppGroup, selected: Boolean) {
            icon.animate().cancel()
            icon.animate()
                .scaleX(0f)
                .scaleY(0f)
                .setDuration(Const.ANIM_DURATION_FAST)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    applyIconState(icon, group, selected)
                    icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(Const.ANIM_DURATION_FAST)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }

        override fun getItemCount() = items.size

        fun moveItem(from: Int, to: Int) {
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun updateList() {
            items = DataManager.instance.sortedGroups.toMutableList()
            notifyDataSetChanged()
        }

        fun animateEnterSelection(toggledUuid: String) {
            val toggledPosition = items.indexOfFirst { it.uuid == toggledUuid }
            for (i in 0 until items.size) {
                if (i == toggledPosition) {
                    notifyItemChanged(i, PAYLOAD_SELECTION_TOGGLE)
                } else {
                    notifyItemChanged(i, PAYLOAD_MODE_CHANGE)
                }
            }
        }

        fun animateSelectionToggled(uuid: String) {
            val position = items.indexOfFirst { it.uuid == uuid }
            if (position >= 0) notifyItemChanged(position, PAYLOAD_SELECTION_TOGGLE)
        }

        fun animateExitSelection(previouslySelected: Set<String>) {
            for (i in 0 until items.size) {
                val payload =
                    if (items[i].uuid in previouslySelected) PAYLOAD_SELECTION_TOGGLE else PAYLOAD_MODE_CHANGE
                notifyItemChanged(i, payload)
            }
        }

        private fun applyIconState(icon: ImageView, group: WebAppGroup, selected: Boolean) {
            if (selected) {
                icon.background = null
                icon.setImageResource(R.drawable.ic_check_24)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(checkIconColor)
            } else {
                icon.imageTintList = null
                icon.background = null
                icon.setImageBitmap(group.resolveIcon())
            }
        }

        private fun showPopupMenu(view: View, group: WebAppGroup) {
            val popup = PopupMenu(this@GroupListActivity, view)
            popup.menuInflater.inflate(R.menu.group_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_share_group -> {
                        shareGroup(group)
                        true
                    }

                    R.id.action_create_shortcut -> {
                        ShortcutHelper.createShortcut(group, this@GroupListActivity)
                        true
                    }

                    R.id.action_edit -> {
                        val intent =
                            Intent(this@GroupListActivity, GroupSettingsActivity::class.java)
                        intent.putExtra(Const.INTENT_GROUP_UUID, group.uuid)
                        startActivity(intent)
                        true
                    }

                    R.id.action_delete -> {
                        showDeleteGroupDialog(group)
                        true
                    }

                    else -> false
                }
            }
            popup.show()
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION_TOGGLE = "selection_toggle"
        private const val PAYLOAD_MODE_CHANGE = "mode_change"
    }
}
