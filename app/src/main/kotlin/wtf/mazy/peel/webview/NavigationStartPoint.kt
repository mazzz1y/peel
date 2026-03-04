package wtf.mazy.peel.webview

import android.webkit.WebView
import androidx.core.net.toUri

/**
 * Tracks the history index where user-driven browsing begins, skipping
 * auto-redirects and challenge pages so back-press exits instead of
 * cycling through entries the user never chose.
 */
class NavigationStartPoint(private val baseUrl: String) {

    private var index: Int? = null
    private var settled = false
    private var visitedForeignHost = false

    fun reset() {
        index = null
        settled = false
        visitedForeignHost = false
    }

    fun onPageFinished() {
        if (!settled && !visitedForeignHost) settled = true
    }

    fun onVisitedHistoryUpdated(view: WebView, url: String?, isReload: Boolean) {
        if (settled || isReload) return
        if (!isBaseHost(url)) {
            visitedForeignHost = true
            return
        }

        index = view.copyBackForwardList().currentIndex

        if (visitedForeignHost) settled = true
    }

    fun canGoBackFrom(currentIndex: Int): Boolean {
        val start = index ?: return true
        return currentIndex > start
    }

    private fun isBaseHost(url: String?): Boolean {
        val urlHost = url?.toUri()?.host?.removePrefix("www.") ?: return false
        val baseHost = baseUrl.toUri().host?.removePrefix("www.") ?: return false
        return urlHost == baseHost || urlHost.endsWith(".$baseHost")
    }
}
