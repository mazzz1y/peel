package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.view.Gravity
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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.controls.BrowserControls
import wtf.mazy.peel.ui.controls.ControlAction
import wtf.mazy.peel.util.isAutomotiveHost

class FloatingControlsView(
    private val parent: FrameLayout,
    webappUuid: String,
    private val actions: List<ControlAction>,
    private val onExpandedChange: ((expanded: Boolean, durationMs: Long) -> Unit)? = null,
) : BrowserControls {

    private data class SavedOffset(val xFraction: Float, val yFraction: Float)

    private class Prefs(context: Context, webappUuid: String, automotive: Boolean) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(
                "${webappUuid}_floating_controls${if (automotive) "_aaos6" else ""}",
                0,
            )

        fun load(): SavedOffset? {
            if (!prefs.contains(KEY_X)) return null
            return SavedOffset(prefs.getFloat(KEY_X, 0f), prefs.getFloat(KEY_Y, 0f))
        }

        fun save(offset: SavedOffset) {
            prefs.edit { putFloat(KEY_X, offset.xFraction); putFloat(KEY_Y, offset.yFraction) }
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
    private val panelTriggerGapPx =
        res.getDimensionPixelSize(R.dimen.floating_controls_panel_trigger_gap)
    private val panelPaddingPx = res.getDimensionPixelSize(R.dimen.floating_controls_panel_padding)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val scrimColor = ContextCompat.getColor(context, R.color.floating_controls_scrim)

    private val automotiveHost = context.isAutomotiveHost()
    private val buttonPrefs = Prefs(context, webappUuid, automotiveHost)
    private val expandHorizontal = automotiveHost
    private val cornerMarginPx = res.getDimensionPixelSize(R.dimen.fab_margin)

    private val panelSpanPx: Int =
        buttonSizePx * actions.size +
            gapPx * (actions.size - 1).coerceAtLeast(0) +
            panelPaddingPx * 2

    private val panelHeightPx: Int get() = if (expandHorizontal) buttonSizePx else panelSpanPx
    private val panelWidthPx: Int get() = if (expandHorizontal) panelSpanPx else buttonSizePx

    private val inflater = LayoutInflater.from(context)
    private val trigger: MaterialCardView =
        inflater.inflate(R.layout.view_floating_trigger, parent, false) as MaterialCardView
    private val triggerIconMenu: ImageView = trigger.findViewById(R.id.floatingTriggerIconMenu)
    private val triggerIconClose: ImageView = trigger.findViewById(R.id.floatingTriggerIconClose)
    private val defaultTriggerBackground = trigger.cardBackgroundColor
    private val defaultTriggerForeground = triggerIconMenu.imageTintList
    private val panel: MaterialCardView =
        inflater.inflate(R.layout.view_floating_panel, parent, false) as MaterialCardView
    private val panelContainer: LinearLayout = panel.findViewById(R.id.floatingPanelActions)
    private val scrim: View = createScrim()

    private val gestureHandler = GestureHandler()

    private var expanded = false
    private var expandDown = false
    private var destroyed = false
    private var translateActiveDot: View? = null
    private var translateActive: Boolean = false
    private var automotivePositionedByGravity = automotiveHost

    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                applyPosition()
                if (expanded) {
                    if (expandHorizontal) {
                        panel.pivotX = resolvedPanelWidthPx().toFloat()
                        panel.pivotY = buttonSizePx / 2f
                    } else {
                        expandDown = shouldExpandDown()
                        panel.pivotY = if (expandDown) 0f else panelHeightPx.toFloat()
                    }
                    positionPanel()
                }
            }
        }

    private val systemBars: Insets
        get() = ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?: Insets.NONE

    init {
        setupLayout()
        attachListeners()
        parent.doOnLayout {
            if (destroyed) return@doOnLayout
            applyPosition()
            panel.visibility = View.GONE
        }
    }

    override fun remove() {
        if (destroyed) return
        destroyed = true
        cancelAllAnimations()
        parent.removeOnLayoutChangeListener(layoutChangeListener)
        parent.removeView(scrim)
        parent.removeView(panel)
        parent.removeView(trigger)
    }

    override fun setHidden(hidden: Boolean) {
        if (destroyed) return
        if (hidden) {
            if (expanded) collapseInstantly()
            gestureHandler.cancel()
        }
        trigger.visibility = if (hidden) View.GONE else View.VISIBLE
        if (!hidden && automotiveHost) {
            parent.bringChildToFront(panel)
            parent.bringChildToFront(trigger)
        }
    }

    private fun setupLayout() {
        trigger.layoutParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx)
        if (expandHorizontal) {
            panelContainer.orientation = LinearLayout.HORIZONTAL
            panelContainer.gravity = Gravity.CENTER_VERTICAL
            panelContainer.setPaddingRelative(panelPaddingPx, 0, panelPaddingPx, 0)
            panelContainer.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            panel.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                panelHeightPx,
            )
        } else {
            panelContainer.setPaddingRelative(0, panelPaddingPx, 0, panelPaddingPx)
            panel.layoutParams = FrameLayout.LayoutParams(panelWidthPx, panelHeightPx)
        }
        populatePanel()
        panel.alpha = 0f
        panel.visibility = View.INVISIBLE
        parent.addView(scrim)
        parent.addView(panel)
        parent.addView(trigger)
        if (automotiveHost) {
            val elevationPx = res.getDimension(R.dimen.floating_controls_elevation)
            trigger.elevation = elevationPx
            panel.elevation = elevationPx
        }
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
        translateActiveDot = null
        actions.forEachIndexed { index, action ->
            val lp = LinearLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
                if (index > 0) {
                    if (expandHorizontal) marginStart = gapPx else topMargin = gapPx
                }
            }
            panelContainer.addView(createActionView(action), lp)
        }
    }

    override fun setTranslateActive(active: Boolean) {
        if (translateActive == active) return
        translateActive = active
        translateActiveDot?.visibility = if (active) View.VISIBLE else View.GONE
    }

    override fun setIncognito(active: Boolean) {
        trigger.setCardBackgroundColor(
            if (active) {
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.incognito_fab))
            } else {
                defaultTriggerBackground
            },
        )
        val foreground = if (active) {
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.incognito_fab_on))
        } else {
            defaultTriggerForeground
        }
        triggerIconMenu.imageTintList = foreground
        triggerIconClose.imageTintList = foreground
    }

    private fun createActionView(action: ControlAction): View {
        val btn = createActionButton(action)
        if (action.tag != ControlAction.TAG_TRANSLATE) return btn
        val wrapper = FrameLayout(context)
        wrapper.addView(
            btn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val dot = inflater.inflate(R.layout.view_indicator_dot, wrapper, false)
        dot.visibility = if (translateActive) View.VISIBLE else View.GONE
        wrapper.addView(dot)
        translateActiveDot = dot
        return wrapper
    }

    private fun createActionButton(action: ControlAction): ImageButton {
        val btn =
            inflater.inflate(R.layout.view_floating_action, panelContainer, false) as ImageButton
        btn.setImageResource(action.iconRes)
        btn.contentDescription = context.getString(action.labelRes)
        btn.setOnClickListener {
            collapse()
            action.onClick()
        }
        action.onLongClick?.let { longClick ->
            btn.setOnLongClickListener {
                btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                collapse()
                longClick()
                true
            }
        }
        return btn
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTriggerInput() {
        trigger.isClickable = true
        trigger.setOnTouchListener { _, event -> gestureHandler.onTouch(event) }
    }

    private fun applyPosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        if (automotiveHost) {
            val saved = buttonPrefs.load()
            if (saved == null || automotivePositionedByGravity) {
                applyAutomotiveGravityPosition()
            } else {
                applyAutomotiveAbsolutePosition(
                    resolveOffset(saved.xFraction, parent.width),
                    resolveOffset(saved.yFraction, parent.height),
                )
            }
            parent.bringChildToFront(panel)
            parent.bringChildToFront(trigger)
            return
        }
        val saved = buttonPrefs.load()
        val x = resolveOffset(saved?.xFraction ?: DEFAULT_X_FRACTION, parent.width)
        val y = resolveOffset(saved?.yFraction ?: DEFAULT_Y_FRACTION, parent.height)
        moveTriggerTo(x, y)
    }

    private fun applyAutomotiveGravityPosition() {
        automotivePositionedByGravity = true
        val lp = trigger.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.NO_GRAVITY
        lp.setMargins(0, 0, 0, 0)
        trigger.layoutParams = lp
        if (parent.width > 0 && parent.height > 0) {
            moveTriggerTo(automotiveDefaultX(), automotiveDefaultY())
        }
    }

    private val automotiveEdgeToEdge: Boolean
        get() = automotiveHost && parent.id == R.id.browser_root

    private fun automotiveEndMarginPx(): Int =
        if (automotiveEdgeToEdge) {
            cornerMarginPx +
                res.getDimensionPixelSize(R.dimen.automotive_floating_controls_end_inset)
        } else {
            cornerMarginPx
        }

    private fun automotiveDefaultX(): Float =
        (parent.width - buttonSizePx - automotiveEndMarginPx()).toFloat().coerceAtLeast(0f)

    private fun automotiveDefaultY(): Float =
        (parent.height - buttonSizePx - cornerMarginPx).toFloat().coerceAtLeast(0f)

    private fun applyAutomotiveAbsolutePosition(x: Float, y: Float) {
        automotivePositionedByGravity = false
        val lp = trigger.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.NO_GRAVITY
        lp.setMargins(0, 0, 0, 0)
        trigger.layoutParams = lp
        moveTriggerTo(x, y)
    }

    private fun moveTriggerToAutomotiveDefault() {
        buttonPrefs.clear()
        applyAutomotiveGravityPosition()
    }

    private fun savePosition() {
        if (parent.width <= 0 || parent.height <= 0) return
        if (automotiveHost && automotivePositionedByGravity) return
        buttonPrefs.save(
            SavedOffset(
                encodeOffset(if (automotiveHost) triggerX() else trigger.x, parent.width),
                encodeOffset(if (automotiveHost) triggerY() else trigger.y, parent.height),
            ),
        )
    }

    private fun resetPosition() {
        if (expanded) collapseInstantly()
        buttonPrefs.clear()
        if (automotiveHost) {
            applyAutomotiveGravityPosition()
        } else {
            applyPosition()
        }
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
        if (automotiveHost) {
            val maxX = (parent.width - buttonSizePx).toFloat().coerceAtLeast(0f)
            val maxY = (parent.height - buttonSizePx).toFloat().coerceAtLeast(0f)
            trigger.x = x.coerceIn(0f, maxX)
            trigger.y = y.coerceIn(0f, maxY)
            return
        }
        val insets = systemBars
        val location = IntArray(2).also { parent.getLocationInWindow(it) }
        val parentTop = location[1]
        val maxX = (parent.width - buttonSizePx).toFloat().coerceAtLeast(0f)
        val minY = (insets.top - parentTop).toFloat().coerceAtLeast(0f)
        val maxY = (parent.height - insets.bottom - buttonSizePx).toFloat().coerceAtLeast(minY)
        trigger.x = x.coerceIn(0f, maxX)
        trigger.y = y.coerceIn(minY, maxY)
    }

    private fun triggerX(): Float =
        if (automotiveHost && automotivePositionedByGravity) automotiveDefaultX() else trigger.x

    private fun triggerY(): Float =
        if (automotiveHost && automotivePositionedByGravity) automotiveDefaultY() else trigger.y

    private fun resolvedPanelWidthPx(): Int =
        if (panel.width > 0) panel.width else panelWidthPx

    private fun positionPanel() {
        if (expandHorizontal) {
            val width = resolvedPanelWidthPx()
            val triggerLeft = triggerX()
            panel.x = (triggerLeft - panelTriggerGapPx - width).coerceAtLeast(0f)
            panel.y = triggerY()
            return
        }
        val down = expandDown
        panel.x = trigger.x
        val preferredY = if (down) {
            trigger.y + buttonSizePx + panelTriggerGapPx
        } else {
            trigger.y - panelTriggerGapPx - panelHeightPx
        }
        val location = IntArray(2).also { parent.getLocationInWindow(it) }
        val minY = (systemBars.top - location[1]).toFloat().coerceAtLeast(0f)
        val maxY = (parent.height - systemBars.bottom - panelHeightPx)
            .toFloat()
            .coerceAtLeast(minY)
        panel.y = preferredY.coerceIn(minY, maxY)
    }

    private fun toggle() {
        if (destroyed) return
        if (expanded) collapse() else expand()
    }

    private fun shouldExpandDown(): Boolean {
        val location = IntArray(2).also { parent.getLocationInWindow(it) }
        val minY = (systemBars.top - location[1]).toFloat().coerceAtLeast(0f)
        val maxY = (parent.height - systemBars.bottom).toFloat()
        val below = maxY - (trigger.y + buttonSizePx + panelTriggerGapPx)
        val above = trigger.y - panelTriggerGapPx - minY
        return below >= panelHeightPx || below >= above
    }

    private fun expand() {
        if (destroyed || expanded) return
        expanded = true
        if (expandHorizontal) {
            if (panel.width <= 0) {
                panel.measure(
                    View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(panelHeightPx, View.MeasureSpec.EXACTLY),
                )
            }
            positionPanel()
            panel.pivotX = resolvedPanelWidthPx().toFloat()
            panel.pivotY = buttonSizePx / 2f
        } else {
            expandDown = shouldExpandDown()
            positionPanel()
            panel.pivotX = buttonSizePx / 2f
            panel.pivotY = if (expandDown) 0f else panelHeightPx.toFloat()
        }
        showPanel()
        animateTriggerIcons(toClose = true)
        fadeScrim(visible = true)
        onExpandedChange?.invoke(true, ANIM_DURATION_MS)
    }

    private fun collapse() {
        if (destroyed || !expanded) return
        expanded = false
        hidePanel()
        animateTriggerIcons(toClose = false)
        fadeScrim(visible = false)
        onExpandedChange?.invoke(false, ANIM_DURATION_MS)
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
        onExpandedChange?.invoke(false, 0L)
    }

    private fun animateTriggerIcons(toClose: Boolean) {
        triggerIconMenu.swapAnimator {
            alpha(if (toClose) 0f else 1f)
            rotation(if (toClose) ICON_MORPH_ROTATION else 0f)
        }
        triggerIconClose.swapAnimator {
            alpha(if (toClose) 1f else 0f)
            rotation(if (toClose) 0f else -ICON_MORPH_ROTATION)
        }
    }

    private fun showPanel() {
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = PANEL_START_SCALE
        panel.scaleY = PANEL_START_SCALE
        panel.swapAnimator {
            alpha(1f)
            scaleX(1f); scaleY(1f)
        }
    }

    private fun hidePanel() {
        panel.swapAnimator {
            alpha(0f)
            scaleX(PANEL_START_SCALE); scaleY(PANEL_START_SCALE)
            withEndAction { panel.visibility = View.GONE }
        }
    }

    private fun fadeScrim(visible: Boolean) {
        if (visible) scrim.visibility = View.VISIBLE
        scrim.swapAnimator {
            alpha(if (visible) SCRIM_ALPHA else 0f)
            if (!visible) withEndAction { scrim.visibility = View.GONE }
        }
    }

    private fun animateTriggerScale(target: Float) {
        trigger.swapAnimator(durationMs = SCALE_ANIM_MS) {
            scaleX(target); scaleY(target)
        }
    }

    private fun cancelAllAnimations() {
        panel.animate().cancel()
        scrim.animate().cancel()
        trigger.animate().cancel()
        triggerIconMenu.animate().cancel()
        triggerIconClose.animate().cancel()
    }

    private inline fun View.swapAnimator(
        durationMs: Long = ANIM_DURATION_MS,
        configure: ViewPropertyAnimator.() -> Unit,
    ) {
        animate().cancel()
        animate()
            .setDuration(durationMs)
            .setInterpolator(FastOutSlowInInterpolator())
            .apply(configure)
            .start()
    }

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
                triggerStartX = triggerX()
                triggerStartY = triggerY()
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
                        if (automotiveHost && automotivePositionedByGravity) {
                            applyAutomotiveAbsolutePosition(triggerX(), triggerY())
                        }
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
