package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import wtf.mazy.peel.R

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

    private data class Position(val x: Float, val y: Float)

    private class Prefs(context: Context, webappUuid: String) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences("${webappUuid}_floating_controls", 0)

        fun load(): Position? {
            if (!prefs.contains(KEY_X)) return null
            return Position(prefs.getFloat(KEY_X, 0f), prefs.getFloat(KEY_Y, 0f))
        }

        fun save(pos: Position) {
            prefs.edit { putFloat(KEY_X, pos.x); putFloat(KEY_Y, pos.y) }
        }

        fun clear() {
            prefs.edit { remove(KEY_X); remove(KEY_Y) }
        }

        private companion object {
            const val KEY_X = "offset_x_pct"
            const val KEY_Y = "offset_y_pct"
        }
    }

    private val context = parent.context
    private val res = context.resources
    private val buttonSizePx = res.getDimensionPixelSize(R.dimen.floating_controls_button_size)
    private val gapPx = res.getDimensionPixelSize(R.dimen.floating_controls_button_gap)
    private val panelTriggerGapPx = res.getDimensionPixelSize(R.dimen.floating_controls_panel_trigger_gap)
    private val panelPaddingPx = res.getDimensionPixelSize(R.dimen.floating_controls_panel_padding)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val scrimColor = ContextCompat.getColor(context, R.color.floating_controls_scrim)
    private val onSurfaceColor: ColorStateList = ColorStateList.valueOf(resolveThemeColor())
    private val rippleBackgroundRes: Int = TypedValue().run {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true)
        resourceId
    }

    private val buttonPrefs = Prefs(context, webappUuid)

    private val actions: List<Action> = buildList {
        add(Action(R.drawable.ic_symbols_home_24, onHome))
        add(Action(R.drawable.ic_symbols_share_24, onShare))
        onFind?.let { add(Action(R.drawable.ic_symbols_search_24, it)) }
        add(Action(R.drawable.ic_symbols_refresh_24, onReload))
        onExtensions?.let { add(Action(R.drawable.ic_symbols_extension_24, it)) }
    }

    private val panelHeightPx: Int =
        buttonSizePx * actions.size +
            gapPx * (actions.size - 1).coerceAtLeast(0) +
            panelPaddingPx * 2

    private val inflater = LayoutInflater.from(context)
    private val trigger: MaterialCardView =
        inflater.inflate(R.layout.view_floating_trigger, parent, false) as MaterialCardView
    private val triggerIconMenu: ImageView = trigger.findViewById(R.id.floatingTriggerIconMenu)
    private val triggerIconClose: ImageView = trigger.findViewById(R.id.floatingTriggerIconClose)
    private val panel: MaterialCardView =
        inflater.inflate(R.layout.view_floating_panel, parent, false) as MaterialCardView
    private val panelContainer: LinearLayout = panel.findViewById(R.id.floatingPanelActions)
    private val scrim: View = createScrim()

    private val gestureHandler = GestureHandler()

    private var expanded = false
    private var expandDown = false

    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                applyPosition()
                if (expanded) positionPanel(expandDown)
            }
        }

    init {
        setupLayout()
        attachListeners()
        parent.doOnLayout {
            applyPosition()
            panel.visibility = View.GONE
        }
    }

    fun remove() {
        cancelAllAnimations()
        parent.removeOnLayoutChangeListener(layoutChangeListener)
        parent.removeView(scrim)
        parent.removeView(panel)
        parent.removeView(trigger)
    }

    fun setHidden(hidden: Boolean) {
        if (hidden) {
            if (expanded) collapseInstantly()
            gestureHandler.cancel()
        }
        trigger.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun setupLayout() {
        trigger.layoutParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx)
        panel.layoutParams = FrameLayout.LayoutParams(buttonSizePx, panelHeightPx)
        populatePanel()
        panel.alpha = 0f
        panel.visibility = View.INVISIBLE
        parent.addView(scrim)
        parent.addView(panel)
        parent.addView(trigger)
    }

    private fun attachListeners() {
        attachTriggerInput()
        parent.addOnLayoutChangeListener(layoutChangeListener)
    }

    private fun createScrim(): View = View(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(scrimColor)
        alpha = 0f
        visibility = View.GONE
        isClickable = true
        setOnClickListener { collapse() }
    }

    private fun populatePanel() {
        panelContainer.removeAllViews()
        actions.forEachIndexed { index, action ->
            val lp = LinearLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
                if (index > 0) topMargin = gapPx
            }
            panelContainer.addView(createActionButton(action), lp)
        }
    }

    private fun createActionButton(action: Action): ImageButton = ImageButton(context).apply {
        background = ContextCompat.getDrawable(context, rippleBackgroundRes)
        setImageResource(action.iconRes)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(0, 0, 0, 0)
        minimumWidth = 0
        minimumHeight = 0
        imageTintList = onSurfaceColor
        setOnClickListener {
            collapse()
            action.onClick()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTriggerInput() {
        trigger.isClickable = true
        trigger.setOnTouchListener { _, event -> gestureHandler.onTouch(event) }
    }

    private fun resolveThemeColor(): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
    }

    private fun statusInsetTop(): Int =
        ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0

    private fun navInsetBottom(): Int =
        ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

    private fun applyPosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        val saved = buttonPrefs.load()
        val x = resolveOffset(saved?.x ?: DEFAULT_X_FRACTION, parent.width)
        val y = resolveOffset(saved?.y ?: DEFAULT_Y_FRACTION, parent.height)
        moveTriggerTo(x, y)
    }

    private fun savePosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        buttonPrefs.save(
            Position(
                encodeOffset(trigger.x, parent.width),
                encodeOffset(trigger.y, parent.height),
            ),
        )
    }

    private fun resetPosition() {
        if (expanded) collapseInstantly()
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

    private fun positionPanel(expandDown: Boolean) {
        val rawY = if (expandDown) {
            trigger.y + buttonSizePx + panelTriggerGapPx
        } else {
            trigger.y - panelTriggerGapPx - panelHeightPx
        }
        val minY = statusInsetTop().toFloat()
        val maxY = (parent.height - navInsetBottom() - panelHeightPx).toFloat().coerceAtLeast(minY)
        panel.x = trigger.x
        panel.y = rawY.coerceIn(minY, maxY)
    }

    private fun toggle() = if (expanded) collapse() else expand()

    private fun shouldExpandDown(): Boolean =
        trigger.y + buttonSizePx / 2f < parent.height / 2f

    private fun expand() {
        if (expanded) return
        expanded = true
        expandDown = shouldExpandDown()
        positionPanel(expandDown)
        panel.pivotX = buttonSizePx / 2f
        panel.pivotY = if (expandDown) 0f else panelHeightPx.toFloat()
        showPanel()
        animateTriggerIcons(toClose = true)
        fadeScrim(visible = true)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        hidePanel()
        animateTriggerIcons(toClose = false)
        fadeScrim(visible = false)
    }

    private fun collapseInstantly() {
        expanded = false
        cancelAllAnimations()
        panel.apply {
            visibility = View.GONE
            alpha = 0f
            scaleX = PANEL_START_SCALE
            scaleY = PANEL_START_SCALE
        }
        triggerIconMenu.alpha = 1f
        triggerIconMenu.rotation = 0f
        triggerIconClose.alpha = 0f
        triggerIconClose.rotation = -ICON_MORPH_ROTATION
        scrim.apply {
            visibility = View.GONE
            alpha = 0f
        }
    }

    private fun animateTriggerIcons(toClose: Boolean) {
        triggerIconMenu.animate().cancel()
        triggerIconClose.animate().cancel()
        triggerIconMenu.animate()
            .alpha(if (toClose) 0f else 1f)
            .rotation(if (toClose) ICON_MORPH_ROTATION else 0f)
            .applyDefaults()
            .start()
        triggerIconClose.animate()
            .alpha(if (toClose) 1f else 0f)
            .rotation(if (toClose) 0f else -ICON_MORPH_ROTATION)
            .applyDefaults()
            .start()
    }

    private fun showPanel() {
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = PANEL_START_SCALE
        panel.scaleY = PANEL_START_SCALE
        panel.animate()
            .alpha(1f)
            .scaleX(1f).scaleY(1f)
            .applyDefaults()
            .start()
    }

    private fun hidePanel() {
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .scaleX(PANEL_START_SCALE).scaleY(PANEL_START_SCALE)
            .applyDefaults()
            .withEndAction { panel.visibility = View.GONE }
            .start()
    }

    private fun fadeScrim(visible: Boolean) {
        scrim.animate().cancel()
        if (visible) scrim.visibility = View.VISIBLE
        scrim.animate()
            .alpha(if (visible) SCRIM_ALPHA else 0f)
            .applyDefaults()
            .withEndAction { if (!visible) scrim.visibility = View.GONE }
            .start()
    }

    private fun animateTriggerScale(target: Float) {
        trigger.animate().cancel()
        trigger.animate()
            .scaleX(target).scaleY(target)
            .setDuration(SCALE_ANIM_MS)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun cancelAllAnimations() {
        panel.animate().cancel()
        scrim.animate().cancel()
        trigger.animate().cancel()
        triggerIconMenu.animate().cancel()
        triggerIconClose.animate().cancel()
    }

    private fun ViewPropertyAnimator.applyDefaults(): ViewPropertyAnimator =
        setDuration(ANIM_DURATION_MS).setInterpolator(FastOutSlowInInterpolator())

    private inner class GestureHandler {
        private var state = GestureState.WAITING
        private var startX = 0f
        private var startY = 0f
        private var triggerStartX = 0f
        private var triggerStartY = 0f

        private val armDragRunnable = Runnable {
            if (state == GestureState.WAITING) {
                if (expanded) collapse()
                state = GestureState.DRAG_ARMED
                trigger.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                animateTriggerScale(DRAG_ARM_SCALE)
            }
        }

        private val resetRunnable = Runnable {
            if (state != GestureState.WAITING && state != GestureState.DRAG_ARMED) return@Runnable
            trigger.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            animateTriggerScale(1f)
            state = GestureState.CANCELLED
            resetPosition()
        }

        fun cancel() {
            removeRunnables()
            if (state.consumesTouch) animateTriggerScale(1f)
            state = GestureState.WAITING
        }

        fun onTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state = GestureState.WAITING
                startX = event.rawX
                startY = event.rawY
                triggerStartX = trigger.x
                triggerStartY = trigger.y
                trigger.postDelayed(armDragRunnable, DRAG_ARM_HOLD_MS)
                trigger.postDelayed(resetRunnable, RESET_HOLD_MS)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                onMove(event); true
            }

            MotionEvent.ACTION_UP -> {
                val wasTap = state == GestureState.WAITING
                finishGesture(saveOnDrag = true)
                if (wasTap) toggle()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                finishGesture(saveOnDrag = false)
                true
            }

            else -> false
        }

        private fun onMove(event: MotionEvent) {
            val dx = event.rawX - startX
            val dy = event.rawY - startY
            val pastSlop = dx * dx + dy * dy > touchSlop * touchSlop
            when (state) {
                GestureState.WAITING -> {
                    if (pastSlop) {
                        state = GestureState.CANCELLED
                        removeRunnables()
                    }
                }

                GestureState.DRAG_ARMED -> {
                    if (pastSlop) {
                        state = GestureState.DRAGGING
                        trigger.removeCallbacks(resetRunnable)
                    }
                    if (state == GestureState.DRAGGING) {
                        moveTriggerTo(triggerStartX + dx, triggerStartY + dy)
                    }
                }

                GestureState.DRAGGING -> moveTriggerTo(triggerStartX + dx, triggerStartY + dy)

                GestureState.CANCELLED -> Unit
            }
        }

        private fun finishGesture(saveOnDrag: Boolean) {
            removeRunnables()
            if (state.consumesTouch) animateTriggerScale(1f)
            if (saveOnDrag && state == GestureState.DRAGGING) savePosition()
            state = GestureState.WAITING
        }

        private fun removeRunnables() {
            trigger.removeCallbacks(armDragRunnable)
            trigger.removeCallbacks(resetRunnable)
        }
    }

    private enum class GestureState {
        WAITING, CANCELLED, DRAG_ARMED, DRAGGING;

        val consumesTouch: Boolean get() = this == DRAG_ARMED || this == DRAGGING
    }

    private companion object {
        const val ANIM_DURATION_MS = 180L
        const val SCALE_ANIM_MS = 120L
        const val DEFAULT_X_FRACTION = -0.035f
        const val DEFAULT_Y_FRACTION = -0.165f
        const val ICON_MORPH_ROTATION = 90f
        const val DRAG_ARM_HOLD_MS = 300L
        const val RESET_HOLD_MS = 1200L
        const val DRAG_ARM_SCALE = 1.15f
        const val SCRIM_ALPHA = 0.4f
        const val PANEL_START_SCALE = 0.85f
    }
}
