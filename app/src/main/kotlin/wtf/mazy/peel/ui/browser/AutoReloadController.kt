package wtf.mazy.peel.ui.browser

import android.os.Handler
import wtf.mazy.peel.model.EffectiveSettings

class AutoReloadController(
    private val mainHandler: Handler,
    private val onReload: () -> Unit,
) {
    private var pendingRunnable: Runnable? = null

    fun start(settings: EffectiveSettings) {
        stop()
        if (!settings.autoReload) return
        val interval = settings.autoReloadInterval.coerceAtLeast(1)
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
