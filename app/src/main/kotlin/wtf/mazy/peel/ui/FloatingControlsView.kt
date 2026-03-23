package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoSession
import android.widget.ImageButton
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import wtf.mazy.peel.R

private class FloatingButtonPrefs(webappUuid: String, parent: FrameLayout) {
    private val prefs: SharedPreferences =
        parent.context.getSharedPreferences("${webappUuid}_floating_controls", 0)

    fun load(): Pair<Float, Float>? {
        val x = prefs.getFloat(KEY_X, -1f)
        val y = prefs.getFloat(KEY_Y, -1f)
        return if (x >= 0f && y >= 0f) x to y else null
    }

    fun save(fracX: Float, fracY: Float) {
        prefs.edit {
            putFloat(KEY_X, fracX)
            putFloat(KEY_Y, fracY)
        }
    }

    private companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}

class FloatingControlsView(
    private val parent: FrameLayout,
    webappUuid: String,
    private val getSession: () -> GeckoSession?,
    private val onHome: () -> Unit,
) {
    private val buttonPrefs = FloatingButtonPrefs(webappUuid, parent)
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

    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyPosition()
                if (expanded) positionActions()
            }
        }

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
        trigger.post { applyPosition() }
        parent.addOnLayoutChangeListener(layoutChangeListener)
    }

    fun remove() {
        savePosition()
        parent.removeOnLayoutChangeListener(layoutChangeListener)
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
                    if (isDragging) savePosition() else toggle()
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
            parent.context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "")
                    },
                    null,
                ),
            )
        }
        actionButtons[2].setOnClickListener { collapse(); getSession()?.reload() }
    }

    private fun applyPosition() {
        val (x, y) = buttonPrefs.load()?.let { (fx, fy) -> fx * parent.width to fy * parent.height }
            ?: ((parent.width - buttonSizePx - marginPx).toFloat() to parent.height * 0.25f)
        moveTriggerTo(x, y)
    }

    private fun savePosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        buttonPrefs.save(trigger.x / parent.width, trigger.y / parent.height)
    }

    private fun moveTriggerTo(x: Float, y: Float) {
        trigger.x = x.coerceIn(0f, (parent.width - buttonSizePx).toFloat())
        trigger.y = y.coerceIn(
            statusInsetTop.toFloat(),
            (parent.height - navInsetBottom - buttonSizePx).toFloat(),
        )
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
            btn.animate().alpha(0f).setDuration(150).withEndAction { btn.visibility = View.GONE }
                .start()
        }
        trigger.animate().rotation(0f).setDuration(150).start()
    }
}
