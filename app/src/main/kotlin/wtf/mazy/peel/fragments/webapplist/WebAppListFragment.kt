package wtf.mazy.peel.fragments.webapplist

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager

class WebAppListFragment : Fragment(R.layout.fragment_web_app_list) {
    private lateinit var adapter: WebAppListAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyStateText: TextView

    private val dragScale = 1.05f

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = WebAppListAdapter(requiredActivity())
        adapter.updateWebAppList()

        list = view.findViewById(R.id.web_app_list)
        emptyStateText = view.findViewById(R.id.empty_state_text)

        list.layoutManager = LinearLayoutManager(requiredActivity())
        list.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(list)

        updateEmptyState()
    }

    fun updateWebAppList() {
        adapter.updateWebAppList()
        updateEmptyState()
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

    private fun requiredActivity(): FragmentActivity {
        return requireNotNull(activity) { "WebAppListFragment is not attached to an activity." }
    }

    private val itemTouchCallback =
        object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, 0)
            }

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

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.let { animatePickedUp(it) }
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                animateDropped(viewHolder.itemView)
                saveCurrentDisplayedOrderOfWebAppsToDisk()
            }
        }

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

    private fun saveCurrentDisplayedOrderOfWebAppsToDisk() {
        for ((i, webapp) in adapter.items.withIndex()) {
            val foundWebApp = DataManager.instance.getWebsites().find { it.uuid == webapp.uuid }
            foundWebApp?.order = i
        }
        DataManager.instance.saveWebAppData()
    }
}
