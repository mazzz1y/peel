package wtf.mazy.peel.ui.controls

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import wtf.mazy.peel.R
import kotlin.math.abs

class PanelControlsView(
    private val container: FrameLayout,
    actions: List<ControlAction>,
    private val onVisibilityChanged: () -> Unit,
) : ActionRowView(
    LayoutInflater.from(container.context)
        .inflate(R.layout.view_panel_controls, container, false) as LinearLayout,
) {

    private val scrollThresholdPx =
        context.resources.getDimensionPixelSize(R.dimen.bar_controls_scroll_threshold)

    private var hostHidden = false
    private var scrollHidden = false
    private var lastScrollY = 0

    init {
        populate(actions) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        container.addView(root)
        container.visibility = View.VISIBLE
        root.doOnLayout { if (!destroyed) onVisibilityChanged() }
    }

    override fun remove() {
        if (destroyed) return
        destroyed = true
        root.animate().cancel()
        container.removeView(root)
        container.visibility = View.GONE
        onVisibilityChanged()
    }

    override fun setHidden(hidden: Boolean) {
        if (destroyed || hostHidden == hidden) return
        hostHidden = hidden
        scrollHidden = false
        root.animate().cancel()
        root.translationY = 0f
        container.visibility = if (hidden) View.GONE else View.VISIBLE
        onVisibilityChanged()
    }

    override fun onContentScrolled(scrollY: Int) {
        if (destroyed || hostHidden) return
        val delta = scrollY - lastScrollY
        if (scrollY <= scrollThresholdPx) {
            lastScrollY = scrollY
            setScrollHidden(false)
            return
        }
        if (abs(delta) < scrollThresholdPx) return
        lastScrollY = scrollY
        setScrollHidden(delta > 0)
    }

    override fun reservedBottomHeight(): Int =
        if (hostHidden || scrollHidden) 0 else root.height

    private fun setScrollHidden(hidden: Boolean) {
        if (scrollHidden == hidden) return
        scrollHidden = hidden
        onVisibilityChanged()
        root.animate().cancel()
        root.animate()
            .translationY(if (hidden) root.height.toFloat() else 0f)
            .setDuration(ANIM_DURATION_MS)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private companion object {
        const val ANIM_DURATION_MS = 180L
    }
}
