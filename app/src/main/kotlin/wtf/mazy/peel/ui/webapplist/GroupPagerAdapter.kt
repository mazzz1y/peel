package wtf.mazy.peel.ui.webapplist

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppGroup
import java.text.BreakIterator

/**
 * ViewPager2 adapter for group tabs.
 *
 * Pages 0..N-1 are groups, filtered by group UUID. If [showUngrouped] is true, the last page shows
 * ungrouped apps labeled "Other".
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
        if (position >= groups.size) return activity.getString(R.string.none)
        val title = groups[position].title
        return leadingEmojis(title, 3) ?: title
    }

    private fun leadingEmojis(text: String, max: Int): String? {
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        var prev = 0
        var count = 0
        while (count < max) {
            val end = iter.next()
            if (end == BreakIterator.DONE) break
            val segment = text.substring(prev, end)
            if (segment.isBlank() || Character.isLetterOrDigit(segment.codePointAt(0))) break
            prev = end
            count++
        }
        return if (count > 0) text.substring(0, prev) else null
    }
}
