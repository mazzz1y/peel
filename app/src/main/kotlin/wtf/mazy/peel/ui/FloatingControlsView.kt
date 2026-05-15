package wtf.mazy.peel.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
    onFind: (() -> Unit)? = null,
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
        onFind?.let { add(Action(R.drawable.ic_symbols_search_24, it)) }
        add(Action(R.drawable.ic_symbols_refresh_24, onReload))
        onExtensions?.let { add(Action(R.drawable.ic_symbols_extension_24, it)) }
    }

    private val trigger = createButton(R.drawable.ic_symbols_more_vert_24, TRIGGER_BG_COLOR).apply {
        imageAlpha = TRIGGER_ICON_ALPHA
    }
    private val actionButtons = actions.map { action ->
        createButton(action.iconRes, ACTION_BG_COLOR).apply {
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
    private var scaleAnimator: AnimatorSet? = null

    private val gestureHandler = GestureHandler()

    init {
        actionButtons.forEach { btn ->
            btn.alpha = 0f
            btn.isClickable = false
            parent.addView(btn)
        }
        parent.addView(trigger)
        gestureHandler.attach()
        parent.addOnLayoutChangeListener(layoutChangeListener)
        parent.doOnLayout { applyPosition() }
    }

    fun remove() {
        scaleAnimator?.cancel()
        parent.removeOnLayoutChangeListener(layoutChangeListener)
        allViews.forEach { parent.removeView(it) }
    }

    fun setHidden(hidden: Boolean) {
        if (hidden) {
            if (expanded) collapse()
            gestureHandler.cancel()
        }
        val visibility = if (hidden) View.GONE else View.VISIBLE
        allViews.forEach { it.visibility = visibility }
    }

    private fun statusInsetTop(): Int =
        ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0

    private fun navInsetBottom(): Int =
        ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

    private fun createButton(iconRes: Int, backgroundColor: Int): ImageButton {
        return ImageButton(themedContext).apply {
            layoutParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx)
            setBackgroundResource(R.drawable.fab_circle_bg)
            (background as? RippleDrawable)?.let { ripple ->
                val mutated = ripple.mutate() as RippleDrawable
                for (i in 0 until mutated.numberOfLayers) {
                    if (mutated.getId(i) == android.R.id.mask) continue
                    (mutated.getDrawable(i) as? GradientDrawable)?.setColor(backgroundColor)
                }
                background = mutated
            }
            setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val inset = (ICON_INSET_DP * density).toInt()
            setPadding(inset, inset, inset, inset)
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
        val isRightAnchored = offsetPx < 0f ||
                (offsetFrac == 0f && offsetFrac.toRawBits() != 0)
        val resolved = if (isRightAnchored) maxPos + offsetPx else offsetPx
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
        animateExpansion(visible = true)
    }

    private fun collapse() {
        expanded = false
        animateExpansion(visible = false)
    }

    private fun animateExpansion(visible: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        val targetRotation = if (visible) TRIGGER_EXPAND_ROTATION else 0f
        actionButtons.forEach { btn ->
            btn.animate().cancel()
            btn.isClickable = visible
            btn.animate().alpha(targetAlpha).setDuration(ANIM_DURATION_MS).start()
        }
        trigger.animate().cancel()
        trigger.animate().rotation(targetRotation).setDuration(ANIM_DURATION_MS).start()
    }

    private fun animateTriggerScale(target: Float) {
        scaleAnimator?.cancel()
        scaleAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(trigger, View.SCALE_X, target),
                ObjectAnimator.ofFloat(trigger, View.SCALE_Y, target),
            )
            duration = SCALE_ANIM_MS
            start()
        }
    }

    private inner class GestureHandler {
        private var state = GestureState.WAITING
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var triggerStartX = 0f
        private var triggerStartY = 0f

        private val dragArmRunnable = Runnable {
            if (state == GestureState.WAITING) {
                state = GestureState.DRAG_ARMED
                trigger.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                animateTriggerScale(DRAG_ARM_SCALE)
            }
        }

        private val resetRunnable = Runnable {
            if (state != GestureState.WAITING && state != GestureState.DRAG_ARMED) return@Runnable
            trigger.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            animateTriggerScale(1f)
            trigger.isPressed = false
            state = GestureState.CANCELLED
            resetPosition()
        }

        @SuppressLint("ClickableViewAccessibility")
        fun attach() {
            val slop = ViewConfiguration.get(themedContext).scaledTouchSlop.toFloat()
            trigger.setOnClickListener { toggle() }
            trigger.setOnTouchListener { v, event -> handle(v, event, slop) }
        }

        fun cancel() {
            cancelHoldRunnables()
            if (state.isHandled) animateTriggerScale(1f)
            trigger.isPressed = false
            state = GestureState.WAITING
        }

        private fun handle(v: View, event: MotionEvent, slop: Float): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    state = GestureState.WAITING
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    triggerStartX = trigger.x
                    triggerStartY = trigger.y
                    trigger.postDelayed(dragArmRunnable, DRAG_ARM_HOLD_MS)
                    trigger.postDelayed(resetRunnable, RESET_HOLD_MS)
                    false
                }

                MotionEvent.ACTION_MOVE -> handleMove(v, event, slop)

                MotionEvent.ACTION_UP -> {
                    cancelHoldRunnables()
                    if (state.isHandled) animateTriggerScale(1f)
                    val wasTap = state == GestureState.WAITING
                    if (state == GestureState.DRAGGING) savePosition()
                    if (!wasTap) v.isPressed = false
                    state = GestureState.WAITING
                    !wasTap
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelHoldRunnables()
                    if (state.isHandled) animateTriggerScale(1f)
                    if (state == GestureState.DRAGGING) savePosition()
                    v.isPressed = false
                    state = GestureState.WAITING
                    false
                }

                else -> false
            }
        }

        private fun handleMove(v: View, event: MotionEvent, slop: Float): Boolean {
            val dx = event.rawX - touchStartX
            val dy = event.rawY - touchStartY
            val pastSlop = dx * dx + dy * dy > slop * slop
            return when (state) {
                GestureState.WAITING -> {
                    if (pastSlop) {
                        state = GestureState.CANCELLED
                        cancelHoldRunnables()
                        v.isPressed = false
                    }
                    false
                }

                GestureState.DRAG_ARMED -> {
                    if (pastSlop) {
                        state = GestureState.DRAGGING
                        trigger.removeCallbacks(resetRunnable)
                    }
                    if (state == GestureState.DRAGGING) {
                        moveTriggerTo(triggerStartX + dx, triggerStartY + dy)
                        if (expanded) positionActions()
                    }
                    true
                }

                GestureState.DRAGGING -> {
                    moveTriggerTo(triggerStartX + dx, triggerStartY + dy)
                    if (expanded) positionActions()
                    true
                }

                GestureState.CANCELLED -> false
            }
        }

        private fun cancelHoldRunnables() {
            trigger.removeCallbacks(dragArmRunnable)
            trigger.removeCallbacks(resetRunnable)
        }
    }

    private enum class GestureState {
        WAITING, CANCELLED, DRAG_ARMED, DRAGGING;

        val isHandled: Boolean get() = this == DRAG_ARMED || this == DRAGGING
    }

    private companion object {
        const val BUTTON_SIZE_DP = 36
        const val BUTTON_GAP_DP = 18
        const val ICON_INSET_DP = 7
        const val ANIM_DURATION_MS = 150L
        const val DEFAULT_X_FRACTION = -0.035f
        const val DEFAULT_Y_FRACTION = -0.165f
        const val TRIGGER_EXPAND_ROTATION = 90f
        const val TRIGGER_ICON_ALPHA = 180
        const val DRAG_ARM_HOLD_MS = 300L
        const val RESET_HOLD_MS = 1200L
        const val DRAG_ARM_SCALE = 1.5f
        const val SCALE_ANIM_MS = 120L
        const val TRIGGER_BG_COLOR = 0x33000000
        const val ACTION_BG_COLOR = 0x66000000
    }
}
