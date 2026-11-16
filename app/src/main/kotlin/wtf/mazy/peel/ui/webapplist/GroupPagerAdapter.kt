package wtf.mazy.peel.ui.webapplist

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.viewpager2.adapter.FragmentStateAdapter
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.util.shortLabel
import java.util.UUID

class GroupPagerAdapter(
    private val activity: FragmentActivity,
) : FragmentStateAdapter(activity) {

    private var pages: List<Page> = emptyList()

    fun submit(groups: List<WebAppGroup>, showUngrouped: Boolean) {
        val next = groups.map { Page(it.uuid, it.title) } + listOfNotNull(UNGROUPED.takeIf { showUngrouped })
        if (next == pages) return
        val diff = DiffUtil.calculateDiff(PageDiff(pages, next))
        pages = next
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment = WebAppListFragment.newInstance(pages[position].key)

    override fun getItemId(position: Int): Long = pages[position].id

    override fun containsItem(itemId: Long): Boolean = pages.any { it.id == itemId }

    fun getPageTitle(position: Int): String {
        val page = pages[position]
        return if (page == UNGROUPED) activity.getString(R.string.ungrouped) else shortLabel(page.title)
    }

    fun groupUuidAt(position: Int): String? = pages.getOrNull(position)?.takeIf { it != UNGROUPED }?.key

    private data class Page(val key: String, val title: String) {
        // Ids key the fragments in saved state, so they must come out the same in a new process:
        // derived from the key, not from the process-local StableIdRegistry.
        val id: Long = UUID.nameUUIDFromBytes(key.toByteArray()).mostSignificantBits
    }

    private class PageDiff(private val old: List<Page>, private val new: List<Page>) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].key == new[newPos].key
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }

    private companion object {
        val UNGROUPED = Page(WebAppListFragment.UNGROUPED_FILTER, "")
    }
}
