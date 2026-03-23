package wtf.mazy.peel.webview

import androidx.core.net.toUri

class NavigationStartPoint(private val baseUrl: String) {

    private var navigationCount = 0
    private var settled = false
    private var visitedForeignHost = false

    fun reset() {
        navigationCount = 0
        settled = true
        visitedForeignHost = false
    }

    fun onPageFinished() {
        if (!settled && !visitedForeignHost) settled = true
    }

    fun onLocationChange(url: String?) {
        navigationCount++
        if (settled) return
        if (!isBaseHost(url)) {
            visitedForeignHost = true
            return
        }
        if (visitedForeignHost) settled = true
    }

    val allowGoBack: Boolean
        get() = settled && navigationCount > 1

    private fun isBaseHost(url: String?): Boolean {
        val urlHost = url?.toUri()?.host?.removePrefix("www.") ?: return false
        val baseHost = baseUrl.toUri().host?.removePrefix("www.") ?: return false
        return urlHost == baseHost || urlHost.endsWith(".$baseHost")
    }
}
