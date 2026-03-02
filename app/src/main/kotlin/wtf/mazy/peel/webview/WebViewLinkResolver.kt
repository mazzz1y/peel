package wtf.mazy.peel.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebView.HitTestResult

class WebViewLinkResolver {

    private var lastUrl: String? = null

    fun hasHit(webView: WebView): Boolean {
        lastUrl = resolveFromHitTest(webView)
        return lastUrl != null
    }

    fun resolve(webView: WebView, callback: (String?) -> Unit) {
        val url = lastUrl ?: resolveFromHitTest(webView)
        lastUrl = null
        callback(url)
    }

    private fun resolveFromHitTest(webView: WebView): String? {
        val hit = webView.hitTestResult
        val url = when (hit.type) {
            HitTestResult.SRC_ANCHOR_TYPE -> hit.extra
            HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> requestNodeHref(webView)
            HitTestResult.IMAGE_TYPE -> hit.extra
            else -> requestNodeHref(webView)
        }
        return url?.takeIf { it.startsWith("http") }
    }

    private fun requestNodeHref(webView: WebView): String? {
        val msg = Handler(Looper.getMainLooper()).obtainMessage()
        webView.requestFocusNodeHref(msg)
        return msg.data?.getString("url")
    }
}
