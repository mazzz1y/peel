package wtf.mazy.peel.browser

import wtf.mazy.peel.util.isSameHost

class StartupAuthReturnTracker(private val baseUrl: String) {
    private var sawNonBaseHost = false
    private var pendingReset = false

    fun onLocationChange(url: String) {
        if (url.startsWith("about:")) return
        if (!isSameHost(baseUrl, url)) {
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
}
