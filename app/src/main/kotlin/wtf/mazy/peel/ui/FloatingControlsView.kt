package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
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
        if (!prefs.contains(KEY_OFFSET_X)) return null
        return prefs.getFloat(KEY_OFFSET_X, 0f) to prefs.getFloat(KEY_OFFSET_Y, 0f)
    }

    fun save(offsetX: Float, offsetY: Float) {
        prefs.edit {
            putFloat(KEY_OFFSET_X, offsetX)
            putFloat(KEY_OFFSET_Y, offsetY)
        }
    }

    fun clear() {
        prefs.edit {
            remove(KEY_OFFSET_X)
            remove(KEY_OFFSET_Y)
        }
    }

    private companion object {
        const val KEY_OFFSET_X = "offset_x_pct"
        const val KEY_OFFSET_Y = "offset_y_pct"
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
    private data class Action(@param:DrawableRes val iconRes: Int, val onClick: () -> Unit)

    private val buttonPrefs = FloatingButtonPrefs(webappUuid, parent)
    private val themedContext =
        ContextThemeWrapper(parent.context, R.style.ThemeOverlay_App_FloatingControls)
    private val density = parent.resources.displayMetrics.density
    private val buttonSizePx = (BUTTON_SIZE_DP * density).toInt()
    private val gapPx = (BUTTON_GAP_DP * density).toInt()
    private val stepPx = buttonSizePx + gapPx

    private val actions = buildList {
        add(Action(R.drawable.ic_symbols_home_24, onHome))
        add(Action(R.drawable.ic_symbols_share_24, onShare))
        add(Action(R.drawable.ic_symbols_refresh_24, onReload))
        onExtensions?.let { add(Action(R.drawable.ic_symbols_extension_24, it)) }
    }

    private val trigger = createButton(R.drawable.ic_symbols_more_vert_24).apply {
        imageAlpha = TRIGGER_ICON_ALPHA
    }
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
    private var resetTriggered = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var triggerStartX = 0f
    private var triggerStartY = 0f

    private val resetRunnable = Runnable {
        resetTriggered = true
        trigger.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        trigger.isPressed = false
        resetPosition()
    }

    init {
        actionButtons.forEach { btn ->
            btn.alpha = 0f
            btn.isClickable = false
            parent.addView(btn)
        }
        parent.addView(trigger)
        setupTriggerGesture()
        parent.addOnLayoutChangeListener(layoutChangeListener)
        parent.doOnLayout { applyPosition() }
    }

    fun remove() {
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
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val inset = (ICON_INSET_DP * density).toInt()
            setPadding(inset, inset, inset, inset)
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
                    resetTriggered = false
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    triggerStartX = trigger.x
                    triggerStartY = trigger.y
                    trigger.postDelayed(resetRunnable, RESET_HOLD_MS)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (dx * dx + dy * dy > dragThreshold * dragThreshold)) {
                        isDragging = true
                        v.isPressed = false
                        trigger.removeCallbacks(resetRunnable)
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
                    trigger.removeCallbacks(resetRunnable)
                    if (isDragging) {
                        savePosition()
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    trigger.removeCallbacks(resetRunnable)
                    if (isDragging) savePosition()
                    false
                }

                else -> false
            }
        }
    }

    private fun applyPosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        val (x, y) = buttonPrefs.load()?.let { (ox, oy) ->
            resolveOffset(ox, parent.width) to resolveOffset(oy, parent.height)
        } ?: (resolveOffset(DEFAULT_X_FRACTION, parent.width) to
                resolveOffset(DEFAULT_Y_FRACTION, parent.height))
        moveTriggerTo(x, y)
    }

    private fun savePosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        buttonPrefs.save(
            encodeOffset(trigger.x, parent.width),
            encodeOffset(trigger.y, parent.height),
        )
    }

    private fun resetPosition() {
        if (expanded) collapse()
        buttonPrefs.clear()
        applyPosition()
    }

    private fun encodeOffset(pos: Float, parentSize: Int): Float {
        val maxPos = (parentSize - buttonSizePx).toFloat()
        val signedPx = if (pos + buttonSizePx / 2f > parentSize / 2f) -(maxPos - pos) else pos
        return signedPx / parentSize
    }

    private fun resolveOffset(offsetFrac: Float, parentSize: Int): Float {
        val maxPos = (parentSize - buttonSizePx).toFloat().coerceAtLeast(0f)
        val offsetPx = offsetFrac * parentSize
        val resolved = if (offsetPx < 0f) maxPos + offsetPx else offsetPx
        return resolved.coerceIn(0f, maxPos)
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
            btn.isClickable = true
            btn.animate().alpha(1f).setDuration(ANIM_DURATION_MS).start()
        }
        trigger.animate().cancel()
        trigger.animate().rotation(TRIGGER_EXPAND_ROTATION).setDuration(ANIM_DURATION_MS).start()
    }

    private fun collapse() {
        expanded = false
        actionButtons.forEach { btn ->
            btn.animate().cancel()
            btn.isClickable = false
            btn.animate().alpha(0f).setDuration(ANIM_DURATION_MS).start()
        }
        trigger.animate().cancel()
        trigger.animate().rotation(0f).setDuration(ANIM_DURATION_MS).start()
    }

    private companion object {
        const val BUTTON_SIZE_DP = 36
        const val BUTTON_GAP_DP = 14
        const val ICON_INSET_DP = 7
        const val ANIM_DURATION_MS = 150L
        const val DEFAULT_X_FRACTION = -0.03f
        const val DEFAULT_Y_FRACTION = -0.035f
        const val TRIGGER_EXPAND_ROTATION = 90f
        const val TRIGGER_ICON_ALPHA = 180
        const val RESET_HOLD_MS = 1200L
    }
}
