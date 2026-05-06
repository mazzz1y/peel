package wtf.mazy.peel.ui.webapplist

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import wtf.mazy.peel.R
import wtf.mazy.peel.model.StableIdRegistry
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.util.shortLabel

class GroupPagerAdapter(
    private val activity: FragmentActivity,
    val groups: List<WebAppGroup>,
    val showUngrouped: Boolean,
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = groups.size + if (showUngrouped) 1 else 0

    override fun createFragment(position: Int): Fragment {
        return if (position < groups.size) {
            WebAppListFragment.newInstance(groups[position].uuid)
        } else {
            WebAppListFragment.newInstance(WebAppListFragment.UNGROUPED_FILTER)
        }
    }

    override fun getItemId(position: Int): Long {
        return if (position < groups.size) groups[position].stableId
        else StableIdRegistry.idFor(WebAppListFragment.UNGROUPED_FILTER)
    }

    override fun containsItem(itemId: Long): Boolean {
        if (showUngrouped && itemId == StableIdRegistry.idFor(WebAppListFragment.UNGROUPED_FILTER)) return true
        return groups.any { it.stableId == itemId }
    }

    fun getPageTitle(position: Int): String {
        if (position >= groups.size) return activity.getString(R.string.ungrouped)
        return shortLabel(groups[position].title)
    }
}
