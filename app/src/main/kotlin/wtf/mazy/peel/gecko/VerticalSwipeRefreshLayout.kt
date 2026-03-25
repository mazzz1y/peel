package wtf.mazy.peel.gecko

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

class VerticalSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    private var initialX = 0f
    private var initialY = 0f
    private var declined = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.x
                initialY = ev.y
                declined = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (declined) return false
                if (ev.pointerCount > 1) {
                    declined = true
                    return false
                }
                val dx = abs(ev.x - initialX)
                val dy = abs(ev.y - initialY)
                if (dx > dy) {
                    declined = true
                    return false
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean {
        if (isEnabled) return false
        return super.onStartNestedScroll(child, target, nestedScrollAxes)
    }

    override fun requestDisallowInterceptTouchEvent(b: Boolean) {
        // Intentionally not propagated — prevents GeckoView from
        // disabling swipe-to-refresh during its own gesture handling.
    }
}
