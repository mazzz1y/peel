package wtf.mazy.peel.gecko

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.view.NestedScrollingChild
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

class NestedGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GeckoView(context, attrs), NestedScrollingChild {

    private var lastY = 0
    private val scrollOffset = IntArray(2)
    private val scrollConsumed = IntArray(2)
    private val childHelper = NestedScrollingChildHelper(this)
    private var inputResult = PanZoomController.INPUT_RESULT_UNHANDLED
    private var allowOverscroll = false
    private var geckoScrollY = 0

    init {
        isNestedScrollingEnabled = true
    }

    override fun canScrollVertically(direction: Int): Boolean {
        if (direction < 0) return geckoScrollY > 0
        return super.canScrollVertically(direction)
    }

    fun updateScrollPosition(y: Int) {
        geckoScrollY = y
    }

    fun resetScrollPosition() {
        geckoScrollY = 0
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val event = MotionEvent.obtain(ev)
        val action = event.actionMasked
        val eventY = event.y.toInt()

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                val allowScroll = !shouldPinOnScreen() && allowOverscroll
                var deltaY = lastY - eventY

                if (allowScroll && dispatchNestedPreScroll(
                        0,
                        deltaY,
                        scrollConsumed,
                        scrollOffset
                    )
                ) {
                    deltaY -= scrollConsumed[1]
                    event.offsetLocation(0f, -scrollOffset[1].toFloat())
                }

                lastY = eventY - scrollOffset[1]

                if (allowScroll && dispatchNestedScroll(
                        0,
                        scrollOffset[1],
                        0,
                        deltaY,
                        scrollOffset
                    )
                ) {
                    lastY -= scrollOffset[1]
                    event.offsetLocation(0f, scrollOffset[1].toFloat())
                }
            }

            MotionEvent.ACTION_DOWN -> {
                inputResult = PanZoomController.INPUT_RESULT_UNHANDLED
                allowOverscroll = false
                parent?.requestDisallowInterceptTouchEvent(true)
                super.onTouchEventForDetailResult(event)
                    .accept { result ->
                        if (result == null) return@accept
                        inputResult = result.handledResult()
                        allowOverscroll =
                            inputResult == PanZoomController.INPUT_RESULT_HANDLED &&
                                    (result.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL) != 0

                        val canOverscrollTop =
                            inputResult != PanZoomController.INPUT_RESULT_HANDLED_CONTENT &&
                                    (result.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP) == 0 &&
                                    (result.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL) != 0
                        if (canOverscrollTop) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }

                        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
                    }
                lastY = eventY
                event.recycle()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopNestedScroll()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        val handled = super.onTouchEvent(event)
        event.recycle()
        return handled
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int): Boolean = childHelper.startNestedScroll(axes)

    override fun stopNestedScroll() = childHelper.stopNestedScroll()

    override fun hasNestedScrollingParent(): Boolean = childHelper.hasNestedScrollingParent()

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?,
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow
    )

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int,
        consumed: IntArray?, offsetInWindow: IntArray?,
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedFling(
        velocityX: Float, velocityY: Float, consumed: Boolean,
    ): Boolean = childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(
        velocityX: Float, velocityY: Float,
    ): Boolean = childHelper.dispatchNestedPreFling(velocityX, velocityY)
}
