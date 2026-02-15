package wtf.mazy.peel.ui.webapplist

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppGroup

/**
 * ViewPager2 adapter for group tabs.
 *
 * Pages 0..N-1 are groups, filtered by group UUID.
 * If [showUngrouped] is true, the last page shows ungrouped apps labeled "None".
 */
class GroupPagerAdapter(
    private val activity: FragmentActivity,
    private val groups: List<WebAppGroup>,
    private val showUngrouped: Boolean,
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = groups.size + if (showUngrouped) 1 else 0

    override fun createFragment(position: Int): Fragment {
        return if (position < groups.size) {
            WebAppListFragment.newInstance(groups[position].uuid)
        } else {
            WebAppListFragment.newInstance(WebAppListFragment.UNGROUPED_FILTER)
        }
    }

    fun getPageTitle(position: Int): String {
        return if (position < groups.size) {
            groups[position].title
        } else {
            activity.getString(R.string.none)
        }
    }
}
