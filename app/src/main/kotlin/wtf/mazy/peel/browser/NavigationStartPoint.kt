package wtf.mazy.peel.browser

import androidx.core.net.toUri

class NavigationStartPoint(baseUrl: String) {
    private val baseHost = baseUrl.toUri().host?.removePrefix("www.")
    private var visitedForeignHost = false
    private var pendingHistoryReset = false
    private var historyResetDone = false

    fun onLocationChange(url: String) {
        if (historyResetDone || url.startsWith("about:")) {
            return
        }
        if (!isBaseHost(url)) {
            visitedForeignHost = true
            return
        }
        pendingHistoryReset = visitedForeignHost
    }

    fun consumeHistoryResetSignal(): Boolean {
        if (!pendingHistoryReset || historyResetDone) return false
        pendingHistoryReset = false
        historyResetDone = true
        return true
    }

    private fun isBaseHost(url: String): Boolean {
        val urlHost = url.toUri().host?.removePrefix("www.") ?: return false
        return baseHost != null && urlHost == baseHost
    }
}
