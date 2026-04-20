package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.annotation.DrawableRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
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
    onHome: () -> Unit,
    onReload: () -> Unit,
    onShare: () -> Unit,
    onExtensions: (() -> Unit)? = null,
) {
    private data class Action(@DrawableRes val iconRes: Int, val onClick: () -> Unit)

    private val buttonPrefs = FloatingButtonPrefs(webappUuid, parent)
    private val themedContext =
        ContextThemeWrapper(parent.context, R.style.ThemeOverlay_App_FloatingControls)
    private val density = parent.resources.displayMetrics.density
    private val buttonSizePx = (BUTTON_SIZE_DP * density).toInt()
    private val gapPx = (BUTTON_GAP_DP * density).toInt()
    private val stepPx = buttonSizePx + gapPx
    private val marginPx = (EDGE_MARGIN_DP * density).toInt()

    private val actions = buildList {
        add(Action(R.drawable.ic_symbols_home_24, onHome))
        add(Action(R.drawable.ic_symbols_share_24, onShare))
        add(Action(R.drawable.ic_symbols_refresh_24, onReload))
        onExtensions?.let { add(Action(R.drawable.ic_symbols_extension_24, it)) }
    }

    private val trigger = createButton(R.drawable.ic_symbols_more_vert_24)
    private val actionButtons = actions.map { action ->
        createButton(action.iconRes).apply {
            setOnClickListener { collapse(); action.onClick() }
        }
    }
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

    init {
        actionButtons.forEach { btn ->
            btn.alpha = 0f
            btn.visibility = View.GONE
            parent.addView(btn)
        }
        parent.addView(trigger)
        setupTriggerGesture()
        parent.addOnLayoutChangeListener(layoutChangeListener)
        parent.doOnLayout { applyPosition() }
    }

    fun remove() {
        savePosition()
        parent.removeOnLayoutChangeListener(layoutChangeListener)
        allViews.forEach { parent.removeView(it) }
    }

    private fun statusInsetTop(): Int =
        ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0

    private fun navInsetBottom(): Int =
        ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

    private fun createButton(iconRes: Int): ImageButton {
        return ImageButton(themedContext).apply {
            layoutParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx)
            setBackgroundResource(R.drawable.fab_circle_bg)
            setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTriggerGesture() {
        val dragThreshold = ViewConfiguration.get(themedContext).scaledTouchSlop.toFloat()

        trigger.setOnClickListener { toggle() }

        trigger.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    triggerStartX = trigger.x
                    triggerStartY = trigger.y
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (dx * dx + dy * dy > dragThreshold * dragThreshold)) {
                        isDragging = true
                        v.isPressed = false
                    }
                    if (isDragging) {
                        moveTriggerTo(triggerStartX + dx, triggerStartY + dy)
                        if (expanded) positionActions()
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        savePosition()
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) savePosition()
                    false
                }

                else -> false
            }
        }
    }

    private fun applyPosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        val (x, y) = buttonPrefs.load()?.let { (fx, fy) -> fx * parent.width to fy * parent.height }
            ?: ((parent.width - buttonSizePx - marginPx).toFloat() to parent.height * DEFAULT_Y_FRACTION)
        moveTriggerTo(x, y)
    }

    private fun savePosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        buttonPrefs.save(trigger.x / parent.width, trigger.y / parent.height)
    }

    private fun moveTriggerTo(x: Float, y: Float) {
        val maxX = (parent.width - buttonSizePx).toFloat().coerceAtLeast(0f)
        val minY = statusInsetTop().toFloat()
        val maxY = (parent.height - navInsetBottom() - buttonSizePx).toFloat().coerceAtLeast(minY)
        trigger.x = x.coerceIn(0f, maxX)
        trigger.y = y.coerceIn(minY, maxY)
    }

    private fun toggle() {
        if (expanded) collapse() else expand()
    }

    private fun shouldExpandDown(): Boolean {
        val requiredSpace = stepPx * actionButtons.size
        val spaceAbove = trigger.y - statusInsetTop()
        val spaceBelow = parent.height - navInsetBottom() - (trigger.y + buttonSizePx)
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
        expandDown = shouldExpandDown()
        positionActions()
        actionButtons.forEach { btn ->
            btn.animate().cancel()
            btn.visibility = View.VISIBLE
            btn.animate().alpha(1f).setDuration(ANIM_DURATION_MS).start()
        }
        trigger.animate().cancel()
        trigger.animate().rotation(TRIGGER_EXPAND_ROTATION).setDuration(ANIM_DURATION_MS).start()
    }

    private fun collapse() {
        expanded = false
        actionButtons.forEach { btn ->
            btn.animate().cancel()
            btn.animate().alpha(0f).setDuration(ANIM_DURATION_MS)
                .withEndAction { if (!expanded) btn.visibility = View.GONE }
                .start()
        }
        trigger.animate().cancel()
        trigger.animate().rotation(0f).setDuration(ANIM_DURATION_MS).start()
    }

    private companion object {
        const val BUTTON_SIZE_DP = 40
        const val BUTTON_GAP_DP = 8
        const val EDGE_MARGIN_DP = 8
        const val ANIM_DURATION_MS = 150L
        const val DEFAULT_Y_FRACTION = 0.25f
        const val TRIGGER_EXPAND_ROTATION = 90f
    }
}
