package wtf.mazy.peel.webview

import android.webkit.WebView
import androidx.core.net.toUri

class NavigationStartPoint(private val baseUrl: String) {

    private var index: Int? = null
    private var wasOnForeignHost = false

    val isSet: Boolean get() = index != null

    fun onVisitedHistoryUpdated(view: WebView, url: String?, isReload: Boolean) {
        if (isSet || isReload) return
        val urlHost = url?.toUri()?.host?.removePrefix("www.") ?: return
        val baseHost = baseUrl.toUri().host?.removePrefix("www.") ?: return
        val isBaseHost = urlHost == baseHost || urlHost.endsWith(".$baseHost")

        if (!isBaseHost) {
            wasOnForeignHost = true
            return
        }

        if (wasOnForeignHost) {
            index = view.copyBackForwardList().currentIndex
        }
    }

    fun canGoBackFrom(currentIndex: Int): Boolean {
        val start = index ?: return true
        return currentIndex > start
    }
}
