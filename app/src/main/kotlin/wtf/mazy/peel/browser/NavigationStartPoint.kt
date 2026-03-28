package wtf.mazy.peel.browser

import androidx.core.net.toUri

class NavigationStartPoint(baseUrl: String) {
    private val baseHost = baseUrl.toUri().host?.removePrefix("www.")
    private var visitedForeignHost = false
    private var initialLoadComplete = false
    private var seenBaseHost = false
    private var lastBaseUrl: String? = null
    var settledAtUrl: String? = null
        private set

    fun onLocationChange(url: String) {
        if (settledAtUrl != null) {
            if (isBaseHost(url) && samePathAs(settledAtUrl!!, url)) settledAtUrl = url
            return
        }
        if (url.startsWith("about:")) return
        if (!isBaseHost(url)) {
            if (!initialLoadComplete) visitedForeignHost = true
            return
        }
        seenBaseHost = true
        lastBaseUrl = url
        if (!visitedForeignHost) return
        settledAtUrl = url
    }

    fun onPageFinished() {
        if (seenBaseHost) initialLoadComplete = true
        if (settledAtUrl == null && !visitedForeignHost) settledAtUrl = lastBaseUrl
    }

    private fun isBaseHost(url: String): Boolean {
        val urlHost = url.toUri().host?.removePrefix("www.") ?: return false
        return baseHost != null && urlHost == baseHost
    }

    private fun samePathAs(a: String, b: String): Boolean {
        val aUri = a.toUri()
        val bUri = b.toUri()
        return aUri.scheme == bUri.scheme && aUri.host == bUri.host && aUri.path == bUri.path
    }
}
