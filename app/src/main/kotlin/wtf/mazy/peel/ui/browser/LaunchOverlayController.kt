package wtf.mazy.peel.ui.browser

import android.os.Handler
import android.view.View

class LaunchOverlayController(
    private val mainHandler: Handler,
    private val isDestroyed: () -> Boolean,
    private val animationDurationMs: Long,
    private val fallbackDelayMs: Long,
) {
    private var overlayView: View? = null
    private var pendingFallback: Runnable? = null
    var isVisible = false
        private set

    fun attach(view: View, themeColor: Int) {
        overlayView = view
        view.setBackgroundColor(themeColor)
    }

    fun arm(onArm: () -> Unit) {
        overlayView?.apply {
            alpha = 1f
            visibility = View.VISIBLE
        }
        isVisible = true
        onArm()
    }

    fun hideFallback(onHiding: (() -> Unit)? = null) {
        release()
        var hidden = false
        val fallback = Runnable {
            if (hidden || isDestroyed()) return@Runnable
            hidden = true
            hideIfNeeded(onHiding)
        }
        pendingFallback = fallback
        mainHandler.postDelayed(fallback, fallbackDelayMs)
    }

    fun hideNow(onHiding: (() -> Unit)? = null) {
        release()
        hideIfNeeded(onHiding)
    }

    fun release() {
        pendingFallback?.let { mainHandler.removeCallbacks(it) }
        pendingFallback = null
    }

    private fun hideIfNeeded(onHiding: (() -> Unit)? = null) {
        if (!isVisible) return
        isVisible = false
        onHiding?.invoke()
        overlayView?.animate()?.alpha(0f)?.setDuration(animationDurationMs)
            ?.withEndAction {
                overlayView?.visibility = View.GONE
            }?.start()
    }
}
