package wtf.mazy.peel.ui.webapplist

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.MainActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.dragReorderCallback

class WebAppListFragment : Fragment(R.layout.fragment_web_app_list) {
    private lateinit var adapter: WebAppListAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyStateText: TextView
    private var itemTouchHelper: ItemTouchHelper? = null

    private val dragScale = 1.05f

    var groupFilter: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupFilter = arguments?.getString(ARG_GROUP_FILTER)

        adapter =
            WebAppListAdapter(
                activity = requiredActivity(),
            )
        adapter.groupFilter = groupFilter
        adapter.updateWebAppList()

        list = view.findViewById(R.id.web_app_list)
        emptyStateText = view.findViewById(R.id.empty_state_text)

        list.layoutManager = LinearLayoutManager(requiredActivity())
        list.adapter = adapter

        attachDragHelper()
        updateEmptyState()

        (activity as? MainActivity)?.registerFragment(groupFilter, this)
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.unregisterFragment(groupFilter)
        super.onDestroyView()
    }

    fun addPendingDeletes(uuids: Collection<String>) {
        DataManager.instance.pendingDeleteUuids.addAll(uuids)
    }

    fun clearPendingDeletes(uuids: Collection<String>) {
        DataManager.instance.pendingDeleteUuids.removeAll(uuids.toSet())
    }

    fun updateWebAppList() {
        adapter.updateWebAppList()
        updateDragEnabled()
        updateEmptyState()
    }

    fun refreshSelectionState() {
        adapter.forceFullRebind()
        updateDragEnabled()
        updateEmptyState()
    }

    fun animateEnterSelection(toggledUuid: String) {
        val toggledPosition = adapter.items.indexOfFirst { it.uuid == toggledUuid }
        for (i in 0 until adapter.items.size) {
            if (i == toggledPosition) {
                adapter.notifyItemChanged(i, WebAppListAdapter.PAYLOAD_SELECTION_TOGGLE)
            } else {
                adapter.notifyItemChanged(i, WebAppListAdapter.PAYLOAD_MODE_CHANGE)
            }
        }
        updateDragEnabled()
    }

    fun animateSelectionToggled(uuid: String) {
        val position = adapter.items.indexOfFirst { it.uuid == uuid }
        if (position >= 0) {
            adapter.notifyItemChanged(position, WebAppListAdapter.PAYLOAD_SELECTION_TOGGLE)
        }
    }

    fun animateExitSelection(previouslySelected: Set<String>) {
        for (i in 0 until adapter.items.size) {
            val payload = if (adapter.items[i].uuid in previouslySelected)
                WebAppListAdapter.PAYLOAD_SELECTION_TOGGLE
            else
                WebAppListAdapter.PAYLOAD_MODE_CHANGE
            adapter.notifyItemChanged(i, payload)
        }
        updateDragEnabled()
    }

    private fun updateEmptyState() {
        if (adapter.items.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            list.visibility = View.GONE
        } else {
            emptyStateText.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
    }

    private fun attachDragHelper() {
        itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper?.attachToRecyclerView(list)
    }

    private fun updateDragEnabled() {
        val selectionHost = activity as? SelectionModeHost
        if (selectionHost?.isInSelectionMode == true) {
            itemTouchHelper?.attachToRecyclerView(null)
        } else {
            itemTouchHelper?.attachToRecyclerView(list)
        }
    }

    private fun requiredActivity(): FragmentActivity {
        return requireNotNull(activity) { "WebAppListFragment is not attached to an activity." }
    }

    private val itemTouchCallback =
        dragReorderCallback(
            onMove = { from, to -> adapter.moveItem(from, to) },
            onDrop = {
                viewLifecycleOwner.lifecycleScope.launch {
                    DataManager.instance.reorderWebApps(adapter.items.map { it.uuid })
                }
            },
            onPickUp = ::animatePickedUp,
            onRelease = ::animateDropped,
        )

    private fun animatePickedUp(view: View) {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, dragScale),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, dragScale),
                ObjectAnimator.ofFloat(view, "elevation", view.elevation, 16f),
            )
            duration = 150
            start()
        }
    }

    private fun animateDropped(view: View) {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", dragScale, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", dragScale, 1f),
                ObjectAnimator.ofFloat(view, "elevation", 16f, 2f),
            )
            duration = 150
            start()
        }
    }

    companion object {
        const val ARG_GROUP_FILTER = "group_filter"
        const val UNGROUPED_FILTER = "__ungrouped__"

        fun newInstance(groupFilter: String?): WebAppListFragment {
            return WebAppListFragment().apply {
                arguments = Bundle().apply { putString(ARG_GROUP_FILTER, groupFilter) }
            }
        }
    }
}
