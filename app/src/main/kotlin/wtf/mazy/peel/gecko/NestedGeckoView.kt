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
    private var nestedOffsetY = 0
    private val childHelper = NestedScrollingChildHelper(this)
    private var inputResult = PanZoomController.INPUT_RESULT_UNHANDLED
    private var allowOverscroll = false
    private var scrollY = 0

    init {
        isNestedScrollingEnabled = true
    }

    override fun canScrollVertically(direction: Int): Boolean {
        if (direction < 0) return scrollY > 0
        return super.canScrollVertically(direction)
    }

    fun updateScrollPosition(y: Int) {
        scrollY = y
    }

    fun resetScrollPosition() {
        scrollY = 0
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
                    nestedOffsetY += scrollOffset[1]
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
                    nestedOffsetY += scrollOffset[1]
                }
            }

            MotionEvent.ACTION_DOWN -> {
                inputResult = PanZoomController.INPUT_RESULT_UNHANDLED
                allowOverscroll = false
                super.onTouchEventForDetailResult(event)
                    .accept { result ->
                        if (result == null) return@accept
                        inputResult = result.handledResult()
                        allowOverscroll =
                            inputResult == PanZoomController.INPUT_RESULT_HANDLED &&
                                    (result.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL) != 0
                        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
                    }
                nestedOffsetY = 0
                lastY = eventY
                event.recycle()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopNestedScroll()
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
