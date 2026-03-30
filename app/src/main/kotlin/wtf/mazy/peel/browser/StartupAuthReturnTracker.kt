package wtf.mazy.peel.browser

import androidx.core.net.toUri

class StartupAuthReturnTracker(baseUrl: String) {
    private val baseHost = baseUrl.toUri().host?.removePrefix("www.")
    private var sawNonBaseHost = false
    private var pendingReset = false

    fun onLocationChange(url: String) {
        if (url.startsWith("about:")) return
        if (!isBaseHost(url)) {
            sawNonBaseHost = true
            return
        }
        pendingReset = sawNonBaseHost
    }

    fun consumeShouldResetHistory(): Boolean {
        val shouldReset = pendingReset
        pendingReset = false
        return shouldReset
    }

    private fun isBaseHost(url: String): Boolean {
        val urlHost = url.toUri().host?.removePrefix("www.") ?: return false
        return baseHost != null && urlHost == baseHost
    }
}
