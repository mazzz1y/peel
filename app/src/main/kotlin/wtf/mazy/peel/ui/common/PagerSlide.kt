package wtf.mazy.peel.ui.common

import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Selects [page] and slides to it from its neighbour, jumping there first when it is further
 * away, as Compose's pager does. A smooth scroll stops exactly on a page that is laid out when it
 * starts, and a main-thread stall during it only cuts it short; one heading for a page not laid
 * out yet runs blind and a stall carries it past the page. Requires an offscreen page limit of
 * at least 1, which keeps the neighbours laid out.
 */
fun ViewPager2.slideTo(page: Int) {
    val from = currentItem
    if (abs(page - from) <= 1) {
        setCurrentItem(page, true)
        return
    }
    // As ViewPager2's own long jump: announced as a smooth scroll, so the tab strip leaves the
    // selection alone, then the inner list is moved to the neighbour, cancelling the blind slide.
    val pages = getChildAt(0) as RecyclerView
    setCurrentItem(page, true)
    pages.scrollToPosition(if (page > from) page - 1 else page + 1)
    pages.doOnNextLayout { if (currentItem == page) pages.smoothScrollToPosition(page) }
}
