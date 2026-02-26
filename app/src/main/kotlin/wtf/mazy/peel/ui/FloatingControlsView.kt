package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import wtf.mazy.peel.R

class FloatingControlsView(
    private val parent: FrameLayout,
    private val getWebView: () -> WebView?,
    private val onHome: () -> Unit,
) {
    private val density = parent.resources.displayMetrics.density
    private val buttonSizePx = (40 * density).toInt()
    private val gapPx = (8 * density).toInt()
    private val stepPx = buttonSizePx + gapPx
    private val marginPx = (8 * density).toInt()

    private val trigger = createButton(R.drawable.ic_baseline_more_vert_24)
    private val actionButtons = listOf(
        createButton(R.drawable.ic_baseline_home_24),
        createButton(R.drawable.ic_baseline_share_24),
        createButton(R.drawable.ic_baseline_refresh_24),
    )
    private val allViews = actionButtons + trigger

    private var expanded = false
    private var expandDown = false
    private var isDragging = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var triggerStartX = 0f
    private var triggerStartY = 0f
    private val statusInsetTop: Int
        get() = ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    private val navInsetBottom: Int
        get() = ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

    init {
        actionButtons.forEach { btn ->
            btn.alpha = 0f
            btn.visibility = View.GONE
            parent.addView(btn)
        }
        parent.addView(trigger)
        setupTouchHandling()
        setupActions()
        placeDefault()
    }

    fun remove() {
        allViews.forEach { parent.removeView(it) }
    }

    private fun createButton(iconRes: Int): ImageButton {
        return ImageButton(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx)
            setBackgroundResource(R.drawable.fab_circle_bg)
            setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            elevation = 12 * density
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling() {
        val dragThreshold = 10 * density

        trigger.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    triggerStartX = trigger.x
                    triggerStartY = trigger.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (dx * dx + dy * dy > dragThreshold * dragThreshold)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        moveTriggerTo(triggerStartX + dx, triggerStartY + dy)
                        if (expanded) positionActions()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggle()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun setupActions() {
        actionButtons[0].setOnClickListener { collapse(); onHome() }
        actionButtons[1].setOnClickListener {
            collapse()
            val url = getWebView()?.url ?: return@setOnClickListener
            parent.context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    },
                    null,
                ),
            )
        }
        actionButtons[2].setOnClickListener { collapse(); getWebView()?.reload() }
    }

    private fun placeDefault() {
        trigger.post {
            val x = (parent.width - buttonSizePx - marginPx).toFloat()
            val y = parent.height * 0.25f
            moveTriggerTo(x, y)
        }
    }

    private fun moveTriggerTo(x: Float, y: Float) {
        val clampedX = x.coerceIn(0f, (parent.width - buttonSizePx).toFloat())
        val clampedY = y.coerceIn(
            statusInsetTop.toFloat(),
            (parent.height - navInsetBottom - buttonSizePx).toFloat()
        )
        trigger.x = clampedX
        trigger.y = clampedY
    }

    private fun toggle() {
        if (expanded) collapse() else expand()
    }

    private fun isInTopHalf(): Boolean {
        val requiredSpace = stepPx * actionButtons.size
        val spaceAbove = trigger.y - statusInsetTop
        val spaceBelow = parent.height - navInsetBottom - (trigger.y + buttonSizePx)
        val preferDown = trigger.y + buttonSizePx / 2f < parent.height / 2f
        if (preferDown && spaceBelow >= requiredSpace) return true
        if (!preferDown && spaceAbove >= requiredSpace) return false
        if (spaceBelow >= requiredSpace) return true
        if (spaceAbove >= requiredSpace) return false
        return spaceBelow > spaceAbove
    }

    private fun positionActions() {
        val direction = if (expandDown) 1f else -1f
        actionButtons.forEachIndexed { i, btn ->
            btn.x = trigger.x
            btn.y = trigger.y + direction * stepPx * (i + 1)
        }
    }

    private fun expand() {
        expanded = true
        expandDown = isInTopHalf()
        positionActions()

        actionButtons.forEach { btn ->
            btn.visibility = View.VISIBLE
            btn.alpha = 0f
            btn.animate().alpha(1f).setDuration(150).start()
        }
        trigger.animate().rotation(90f).setDuration(150).start()
    }

    private fun collapse() {
        expanded = false
        actionButtons.forEach { btn ->
            btn.animate().alpha(0f).setDuration(150)
                .withEndAction { btn.visibility = View.GONE }
                .start()
        }
        trigger.animate().rotation(0f).setDuration(150).start()
    }
}
