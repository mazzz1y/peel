package wtf.mazy.peel.ui.browser

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.view.isVisible
import wtf.mazy.peel.R
import kotlin.math.abs
import kotlin.math.min

/**
 * D-pad driven pointer for hosts without a touchscreen. Synthesising a tap is the only way to
 * reach arbitrary page elements: Gecko dropped spatial navigation, so arrow keys merely scroll.
 */
class VirtualCursorController(
    private val content: FrameLayout,
    private val onBackPressedDispatcher: OnBackPressedDispatcher,
    private val dispatchTouch: (MotionEvent) -> Unit,
    private val onScroll: (dx: Int, dy: Int) -> Unit,
) {
    private val size = content.resources.getDimensionPixelSize(R.dimen.virtual_cursor_size)
    private val density = content.resources.displayMetrics.density

    private val pointer = ImageView(content.context).apply {
        setImageResource(R.drawable.virtual_cursor)
        isVisible = false
        // Above the floating controls, which sit at 6dp.
        elevation = content.resources.getDimension(R.dimen.virtual_cursor_elevation)
        layoutParams = FrameLayout.LayoutParams(size, size)
    }

    private val heldKeys = mutableSetOf<Int>()
    private val windowLocation = IntArray(2)
    private var frameStartedAt = 0L
    private var lastMotionAt = 0L
    private var speed = 0f
    private var x = 0f
    private var y = 0f
    private var downTime = 0L
    private var pressed = false

    private val stepRunnable = object : Runnable {
        override fun run() {
            if (heldKeys.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            val seconds = (now - frameStartedAt) / 1000f
            frameStartedAt = now
            lastMotionAt = now
            speed = min(MAX_SPEED_DP_PER_SECOND, speed + ACCELERATION_DP_PER_SECOND * seconds)
            step(seconds)
            content.postOnAnimation(this)
        }
    }

    // Back arrives through the dispatcher, not dispatchKeyEvent, once predictive back is enabled.
    // Registered on show rather than here: the dispatcher runs the most recently added callback
    // first, so this has to outrank the host's own back handling rather than merely predate it.
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = hide()
    }

    init {
        content.addView(pointer)
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in AXES) {
            onAxisKey(event)
            return true
        }
        if (!pointer.isVisible || event.keyCode !in SELECT_KEYS) return false
        onSelectKey(event)
        return true
    }

    // A key held as the window loses focus never sees its ACTION_UP, which would leave the glide
    // running and Gecko holding an unfinished touch sequence.
    fun cancel() {
        stop()
        if (!pressed) return
        pressed = false
        dispatchTap(MotionEvent.ACTION_CANCEL)
    }

    fun release() {
        hide()
        content.removeView(pointer)
    }

    private fun onAxisKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!pointer.isVisible) show()
                // The glide loop already owns sustained motion.
                if (event.repeatCount > 0) return
                // Some hosts report a held key as repeated down/up pairs rather than one sustained
                // press, so speed has to survive between presses to ever build up to a glide.
                val now = SystemClock.uptimeMillis()
                if (now - lastMotionAt > IDLE_RESET_MS) speed = INITIAL_SPEED_DP_PER_SECOND
                lastMotionAt = now
                // A tap is shorter than a frame, so it gets no loop tick of its own.
                move(axisOf(event.keyCode), speed * density * STEP_SECONDS)
                speed = min(
                    MAX_SPEED_DP_PER_SECOND,
                    speed + ACCELERATION_DP_PER_SECOND * STEP_SECONDS,
                )
                if (heldKeys.add(event.keyCode) && heldKeys.size == 1) start()
            }

            KeyEvent.ACTION_UP -> {
                heldKeys.remove(event.keyCode)
                if (heldKeys.isEmpty()) stop()
            }
        }
    }

    private fun onSelectKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (pressed) return
                pressed = true
                downTime = SystemClock.uptimeMillis()
                dispatchTap(MotionEvent.ACTION_DOWN)
            }

            KeyEvent.ACTION_UP -> {
                if (!pressed) return
                pressed = false
                dispatchTap(MotionEvent.ACTION_UP)
            }
        }
    }

    private fun start() {
        frameStartedAt = SystemClock.uptimeMillis()
        content.postOnAnimation(stepRunnable)
    }

    private fun stop() {
        heldKeys.clear()
        content.removeCallbacks(stepRunnable)
    }

    private fun show() {
        x = (content.width - size) / 2f
        y = (content.height - size) / 2f
        pointer.isVisible = true
        onBackPressedDispatcher.addCallback(backCallback)
        applyPosition()
    }

    private fun hide() {
        cancel()
        pointer.isVisible = false
        backCallback.remove()
    }

    private fun step(seconds: Float) {
        val distance = speed * density * seconds
        var dx = 0f
        var dy = 0f
        heldKeys.forEach { key ->
            when (axisOf(key)) {
                Axis.LEFT -> dx -= distance
                Axis.RIGHT -> dx += distance
                Axis.UP -> dy -= distance
                Axis.DOWN -> dy += distance
            }
        }
        moveBy(dx, dy)
    }

    private fun move(axis: Axis, distance: Float) = when (axis) {
        Axis.LEFT -> moveBy(-distance, 0f)
        Axis.RIGHT -> moveBy(distance, 0f)
        Axis.UP -> moveBy(0f, -distance)
        Axis.DOWN -> moveBy(0f, distance)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val maxX = (content.width - size).toFloat().coerceAtLeast(0f)
        val maxY = (content.height - size).toFloat().coerceAtLeast(0f)
        val clampedX = (x + dx).coerceIn(0f, maxX)
        val clampedY = (y + dy).coerceIn(0f, maxY)
        // Motion lost to the clamp becomes scrolling, reaching content outside the viewport.
        val scrollX = edgeOverflow(x + dx, clampedX)
        val scrollY = edgeOverflow(y + dy, clampedY)
        x = clampedX
        y = clampedY
        applyPosition()
        if (scrollX != 0 || scrollY != 0) onScroll(scrollX, scrollY)
    }

    private fun axisOf(keyCode: Int): Axis = AXES.getValue(keyCode)

    private fun edgeOverflow(requested: Float, clamped: Float): Int {
        val overflow = requested - clamped
        return if (abs(overflow) < 1f) 0 else overflow.toInt()
    }

    private fun applyPosition() {
        pointer.x = x
        pointer.y = y
    }

    private fun dispatchTap(action: Int) {
        content.getLocationInWindow(windowLocation)
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            windowLocation[0] + x + size * HOTSPOT_X,
            windowLocation[1] + y + size * HOTSPOT_Y,
            0,
        )
        dispatchTouch(event)
        event.recycle()
    }

    private enum class Axis { LEFT, RIGHT, UP, DOWN }

    private companion object {
        // Arrow tip within the drawable, whose viewport is 960 square.
        const val HOTSPOT_X = 240f / 960f
        const val HOTSPOT_Y = 80f / 960f

        // What one untimed key press counts as.
        const val STEP_SECONDS = 0.1f
        const val IDLE_RESET_MS = 250L
        const val INITIAL_SPEED_DP_PER_SECOND = 220f
        const val MAX_SPEED_DP_PER_SECOND = 1400f
        const val ACCELERATION_DP_PER_SECOND = 1200f

        // Which one a remote's OK button reports varies by device.
        val SELECT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )

        val AXES = mapOf(
            KeyEvent.KEYCODE_DPAD_LEFT to Axis.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to Axis.RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to Axis.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Axis.DOWN,
        )
    }
}
