package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.PopupMenu
import android.widget.TextView
import androidx.activity.addCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Collections
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.ui.dialog.showInputDialog

class GroupListActivity : AppCompatActivity() {

    private lateinit var adapter: GroupListAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyStateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.groups)

        onBackPressedDispatcher.addCallback(this) { finish() }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = GroupListAdapter()
        list = findViewById(R.id.group_list)
        emptyStateText = findViewById(R.id.empty_state_text)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(list)

        findViewById<FloatingActionButton>(R.id.fab_add_group).setOnClickListener {
            showAddGroupDialog()
        }

        updateList()
    }

    override fun onResume() {
        super.onResume()
        DataManager.instance.loadAppData()
        updateList()
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
        showInputDialog(
            titleRes = R.string.add_group,
            hintRes = R.string.group_name_hint,
        ) { name ->
            val group = WebAppGroup(
                title = name,
                order = DataManager.instance.getGroups().size,
            )
            DataManager.instance.addGroup(group)
            updateList()

            val intent = Intent(this, GroupSettingsActivity::class.java)
            intent.putExtra(Const.INTENT_GROUP_UUID, group.uuid)
            startActivity(intent)
        }
    }

    private fun showDeleteGroupDialog(group: WebAppGroup) {
        val appsInGroup = DataManager.instance.activeWebsitesForGroup(group.uuid)
        if (appsInGroup.isEmpty()) {
            DataManager.instance.removeGroup(group, ungroupApps = false)
            updateList()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_group, null)
        val message = dialogView.findViewById<TextView>(R.id.delete_group_message)
        val switchUngroup =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchUngroupApps)

        message.text = getString(R.string.delete_group_confirm, group.title, appsInGroup.size)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_group_title))
            .setView(dialogView)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (!switchUngroup.isChecked) {
                    appsInGroup.forEach { it.cleanupWebAppData(this) }
                }
                DataManager.instance.removeGroup(group, ungroupApps = switchUngroup.isChecked)
                updateList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val dragCallback =
        object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = true

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                saveOrder()
            }
        }

    private fun saveOrder() {
        for ((i, group) in adapter.items.withIndex()) {
            DataManager.instance.getGroup(group.uuid)?.order = i
        }
        DataManager.instance.saveGroupData()
    }

    inner class GroupListAdapter : RecyclerView.Adapter<GroupListAdapter.ViewHolder>() {
        var items: MutableList<WebAppGroup> = mutableListOf()
            private set

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconView: ImageView = itemView.findViewById(R.id.groupIcon)
            val titleView: TextView = itemView.findViewById(R.id.groupTitle)
            val countView: TextView = itemView.findViewById(R.id.groupAppCount)
            val menuButton: ImageView = itemView.findViewById(R.id.btnMenu)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.group_list_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = items[position]
            holder.titleView.text = group.title

            holder.iconView.setImageBitmap(group.resolveIcon())

            val count = DataManager.instance.activeWebsitesForGroup(group.uuid).size
            holder.countView.text =
                resources.getQuantityString(R.plurals.group_app_count, count, count)

            holder.itemView.setOnClickListener {
                val intent = Intent(this@GroupListActivity, GroupSettingsActivity::class.java)
                intent.putExtra(Const.INTENT_GROUP_UUID, group.uuid)
                startActivity(intent)
            }

            holder.menuButton.setOnClickListener { view -> showPopupMenu(view, group) }
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

        private fun showPopupMenu(view: View, group: WebAppGroup) {
            val popup = PopupMenu(this@GroupListActivity, view)
            popup.menuInflater.inflate(R.menu.group_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
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
}
