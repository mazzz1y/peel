package wtf.mazy.peel.ui.webview

import android.os.Handler
import android.view.View
import android.webkit.WebView

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

    fun hideWhenReady(webView: WebView?, onHiding: (() -> Unit)? = null) {
        if (webView == null) {
            hideIfNeeded(onHiding)
            return
        }

        var hidden = false
        val fallback = Runnable {
            if (hidden || isDestroyed()) return@Runnable
            hidden = true
            hideIfNeeded(onHiding)
        }
        pendingFallback = fallback
        mainHandler.postDelayed(fallback, fallbackDelayMs)

        webView.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (hidden || isDestroyed()) return
                hidden = true
                pendingFallback?.let { mainHandler.removeCallbacks(it) }
                pendingFallback = null
                hideIfNeeded(onHiding)
            }
        })
    }

    fun release() {
        pendingFallback?.let { mainHandler.removeCallbacks(it) }
        pendingFallback = null
    }

    private fun hideIfNeeded(onHiding: (() -> Unit)? = null) {
        if (!isVisible) return
        onHiding?.invoke()
        overlayView?.animate()?.alpha(0f)?.setDuration(animationDurationMs)
            ?.withEndAction {
                isVisible = false
                overlayView?.visibility = View.GONE
            }?.start()
    }
}
