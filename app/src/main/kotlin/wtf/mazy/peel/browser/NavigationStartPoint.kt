package wtf.mazy.peel.browser

import androidx.core.net.toUri

class NavigationStartPoint(baseUrl: String) {
    private val baseHost = baseUrl.toUri().host?.removePrefix("www.")
    private var visitedForeignHost = false
    private var initialLoadComplete = false
    private var seenBaseHost = false
    var settledAtUrl: String? = null
        private set

    fun onLocationChange(url: String) {
        if (settledAtUrl != null) return
        if (url.startsWith("about:")) return
        if (!isBaseHost(url)) {
            if (!initialLoadComplete) visitedForeignHost = true
            return
        }
        seenBaseHost = true
        if (!visitedForeignHost) return
        settledAtUrl = url
    }

    fun onPageFinished() {
        if (seenBaseHost) initialLoadComplete = true
    }

    private fun isBaseHost(url: String): Boolean {
        val urlHost = url.toUri().host?.removePrefix("www.") ?: return false
        return baseHost != null && urlHost == baseHost
    }
}
