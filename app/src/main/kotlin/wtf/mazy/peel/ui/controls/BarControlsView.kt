package wtf.mazy.peel.ui.controls

import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import wtf.mazy.peel.R
import kotlin.math.abs

class BarControlsView(
    private val parent: FrameLayout,
    actions: List<ControlAction>,
) : ActionRowView(
    LayoutInflater.from(parent.context)
        .inflate(R.layout.view_bar_controls, parent, false) as LinearLayout,
) {

    private val marginPx =
        context.resources.getDimensionPixelSize(R.dimen.bar_controls_margin)
    private val bottomMarginPx =
        context.resources.getDimensionPixelSize(R.dimen.bar_controls_margin_bottom)
    private val scrollThresholdPx =
        context.resources.getDimensionPixelSize(R.dimen.bar_controls_scroll_threshold)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val autoHideRunnable = Runnable { animateTo(hidden = true) }

    private var hostHidden = false
    private var shown = false
    private var tracking = false
    private var trackingStartY = 0f
    private var trackingStartOffset = 0f
    private var trackingFromShown = false
    private var shownAt = 0L
    private var lastContentScrollY = 0

    init {
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(marginPx, marginPx, marginPx, bottomMarginPx)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        root.background = root.background.mutate().apply { alpha = BACKGROUND_ALPHA }
        val slotWidthPx =
            context.resources.getDimensionPixelSize(R.dimen.bar_controls_slot_width)
        val buttonGapPx =
            context.resources.getDimensionPixelSize(R.dimen.bar_controls_button_gap)
        populate(actions) { index ->
            LinearLayout.LayoutParams(
                slotWidthPx,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                if (index > 0) marginStart = buttonGapPx
            }
        }
        parent.addView(root)
        root.doOnLayout { it.translationY = hiddenOffset() }
    }

    override fun remove() {
        if (destroyed) return
        destroyed = true
        tracking = false
        root.removeCallbacks(autoHideRunnable)
        root.animate().cancel()
        parent.removeView(root)
    }

    override fun setHidden(hidden: Boolean) {
        if (destroyed) return
        hostHidden = hidden
        tracking = false
        shown = false
        shownAt = 0L
        root.removeCallbacks(autoHideRunnable)
        root.animate().cancel()
        root.visibility = if (hidden) View.GONE else View.VISIBLE
        if (!hidden) root.translationY = hiddenOffset()
    }

    override fun onContentScrolled(scrollY: Int) {
        if (destroyed || hostHidden || !shown || tracking) return
        if (SystemClock.uptimeMillis() - shownAt < SCROLL_GRACE_MS) {
            lastContentScrollY = scrollY
            return
        }
        if (abs(scrollY - lastContentScrollY) < scrollThresholdPx) return
        lastContentScrollY = scrollY
        animateTo(hidden = true)
    }

    override fun onHostTouchEvent(event: MotionEvent): Boolean {
        if (destroyed || hostHidden) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (shown) restartAutoHide()
                tracking = root.height > 0 && event.y >= root.top
                trackingStartY = event.y
                trackingFromShown = shown
                trackingStartOffset = if (shown) 0f else hiddenOffset()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val delta = event.y - trackingStartY
                if (abs(delta) <= touchSlop) return false
                if (isWrongDirection(delta)) {
                    abortDrag()
                    return false
                }
                root.removeCallbacks(autoHideRunnable)
                root.animate().cancel()
                root.translationY =
                    (trackingStartOffset + delta).coerceIn(0f, hiddenOffset())
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                val delta = event.y - trackingStartY
                if (abs(delta) <= touchSlop || isWrongDirection(delta)) {
                    abortDrag()
                    return false
                }
                tracking = false
                val committed = abs(delta) >= commitDistance()
                animateTo(hidden = if (trackingFromShown) committed else !committed)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (tracking) abortDrag()
        }
        return false
    }

    private fun animateTo(hidden: Boolean) {
        if (destroyed) return
        shown = !hidden
        root.removeCallbacks(autoHideRunnable)
        root.animate().cancel()
        root.animate()
            .translationY(if (hidden) hiddenOffset() else 0f)
            .setDuration(ANIM_DURATION_MS)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
        if (!hidden) restartAutoHide()
    }

    private fun restartAutoHide() {
        shownAt = SystemClock.uptimeMillis()
        root.removeCallbacks(autoHideRunnable)
        root.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun abortDrag() {
        tracking = false
        root.translationY = trackingStartOffset
        if (trackingFromShown) restartAutoHide()
    }

    private fun isWrongDirection(delta: Float): Boolean =
        if (trackingFromShown) delta < 0f else delta > 0f

    private fun hiddenOffset(): Float = (root.height + bottomMarginPx).toFloat()

    private fun commitDistance(): Float = root.height * COMMIT_FRACTION

    private companion object {
        const val ANIM_DURATION_MS = 180L
        const val AUTO_HIDE_DELAY_MS = 3_000L
        const val BACKGROUND_ALPHA = 217
        const val COMMIT_FRACTION = 0.5f
        const val SCROLL_GRACE_MS = 400L
    }
}
