package wtf.mazy.peel.ui.webapplist

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import wtf.mazy.peel.R
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
        val key = if (position < groups.size) groups[position].uuid else UNGROUPED_ID_KEY
        return key.hashCode().toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        if (showUngrouped && itemId == UNGROUPED_ID_KEY.hashCode().toLong()) return true
        return groups.any { it.uuid.hashCode().toLong() == itemId }
    }

    fun getPageTitle(position: Int): String {
        if (position >= groups.size) return activity.getString(R.string.ungrouped)
        return shortLabel(groups[position].title)
    }

    companion object {
        private const val UNGROUPED_ID_KEY = "__ungrouped__"
    }
}
