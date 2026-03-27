package wtf.mazy.peel.browser

import androidx.core.net.toUri

class NavigationStartPoint(private val baseUrl: String) {
    private var visitedForeignHost = false
    private var frozen = false
    var settledAtUrl: String? = null
        private set

    fun onLocationChange(url: String) {
        if (settledAtUrl != null) return
        if (!isBaseHost(url)) {
            if (!frozen) visitedForeignHost = true
            return
        }
        if (!visitedForeignHost) return
        settledAtUrl = url
    }

    fun onPageFinished() {
        frozen = true
    }

    private fun isBaseHost(url: String): Boolean {
        val urlHost = url.toUri().host?.removePrefix("www.") ?: return false
        val baseHost = baseUrl.toUri().host?.removePrefix("www.") ?: return false
        return urlHost == baseHost
    }
}
