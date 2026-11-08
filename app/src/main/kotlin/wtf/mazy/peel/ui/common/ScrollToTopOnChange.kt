package wtf.mazy.peel.ui.common

import androidx.recyclerview.widget.RecyclerView

fun RecyclerView.Adapter<*>.scrollToTopOnChange(list: () -> RecyclerView) {
    // A scroll pending over an empty layout makes the layout manager drop the rows outright,
    // without their exit animation.
    fun scrollToTop() {
        if (itemCount > 0) list().scrollToPosition(0)
    }
    registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = scrollToTop()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scrollToTop()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scrollToTop()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            scrollToTop()
    })
}
