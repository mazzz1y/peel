package wtf.mazy.peel.browser

import androidx.core.net.toUri

class NavigationStartPoint(private val baseUrl: String) {
    private var settled = false
    private var visitedForeignHost = false

    fun onLocationChange(url: String): Boolean {
        if (settled) return false
        if (!isBaseHost(url)) {
            visitedForeignHost = true
            return false
        }
        settled = true
        return visitedForeignHost
    }

    fun onPageFinished() {
        settled = true
    }

    private fun isBaseHost(url: String): Boolean {
        val urlHost = url.toUri().host?.removePrefix("www.") ?: return false
        val baseHost = baseUrl.toUri().host?.removePrefix("www.") ?: return false
        return urlHost == baseHost
    }
}
