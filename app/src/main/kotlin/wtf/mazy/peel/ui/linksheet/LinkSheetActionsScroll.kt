package wtf.mazy.peel.ui.linksheet

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/**
 * Scrolls the action groups when they share a line. A plain HorizontalScrollView takes any
 * sideways drag even with nothing to scroll, which would starve a group on its own line of it.
 */
class LinkSheetActionsScroll @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        (canScrollHorizontally(1) || canScrollHorizontally(-1)) && super.onInterceptTouchEvent(ev)
}
