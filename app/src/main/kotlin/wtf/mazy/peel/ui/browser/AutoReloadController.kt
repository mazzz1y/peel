package wtf.mazy.peel.ui.browser

import android.os.Handler
import wtf.mazy.peel.model.WebAppSettings

class AutoReloadController(
    private val mainHandler: Handler,
    private val onReload: () -> Unit,
) {
    private var pendingRunnable: Runnable? = null

    fun start(settings: WebAppSettings) {
        stop()
        if (settings.isAutoReload != true) return
        val interval = settings.timeAutoReload?.coerceAtLeast(1) ?: return
        val runnable = Runnable {
            onReload()
            start(settings)
        }
        pendingRunnable = runnable
        mainHandler.postDelayed(runnable, interval * 1000L)
    }

    fun stop() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRunnable = null
    }
}
