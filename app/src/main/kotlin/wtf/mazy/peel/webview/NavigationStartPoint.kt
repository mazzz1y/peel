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
    private var pendingGesture = false

    fun onNavigationStarted(hasGesture: Boolean) {
        pendingGesture = hasGesture
    }

    fun onVisitedHistoryUpdated(view: WebView, url: String?, isReload: Boolean) {
        if (settled || isReload) return
        if (!isBaseHost(url)) {
            visitedForeignHost = true
            return
        }

        val currentIndex = view.copyBackForwardList().currentIndex

        when {
            visitedForeignHost -> settle(currentIndex)
            pendingGesture -> settled = true
            else -> index = currentIndex
        }
    }

    fun canGoBackFrom(currentIndex: Int): Boolean {
        val start = index ?: return true
        return currentIndex > start
    }

    private fun settle(at: Int) {
        index = at
        settled = true
    }

    private fun isBaseHost(url: String?): Boolean {
        val urlHost = url?.toUri()?.host?.removePrefix("www.") ?: return false
        val baseHost = baseUrl.toUri().host?.removePrefix("www.") ?: return false
        return urlHost == baseHost || urlHost.endsWith(".$baseHost")
    }
}
