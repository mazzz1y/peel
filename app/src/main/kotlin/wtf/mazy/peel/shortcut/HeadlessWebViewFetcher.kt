package wtf.mazy.peel.shortcut

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import org.json.JSONObject
import org.jsoup.Jsoup
import wtf.mazy.peel.shortcut.ShortcutIconUtils.getWidthFromIcon
import wtf.mazy.peel.util.Const

class HeadlessWebViewFetcher(
    context: Context,
    private val url: String,
    private val onResult: (title: String?, icon: Bitmap?) -> Unit,
) {
    private val webView = WebView(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private val timeoutRunnable = Runnable { finish(null, null) }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        webView.settings.javaScriptEnabled = true
        webView.settings.blockNetworkImage = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String) {
                extractHtml(view) { html ->
                    Thread {
                        val result = html?.let { resolve(it, loadedUrl) }
                        handler.post { finish(result?.first, result?.second) }
                    }.start()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) finish(null, null)
            }
        }

        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        webView.loadUrl(url)
    }

    private fun extractHtml(view: WebView, callback: (String?) -> Unit) {
        view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = raw
                ?.removeSurrounding("\"")
                ?.replace("\\u003C", "<")
                ?.replace("\\u003E", ">")
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\\\", "\\")
            callback(html)
        }
    }

    private fun resolve(html: String, pageUrl: String): Pair<String?, Bitmap?> {
        val doc = Jsoup.parse(html, pageUrl)
        val icons = TreeMap<Int, String>()
        var title: String? = null

        val manifestHref = doc.select("link[rel=manifest]").first()?.absUrl("href")
        if (!manifestHref.isNullOrEmpty()) {
            title = parseManifest(manifestHref, icons)
        }

        if (title == null) {
            title = doc.title().takeIf { it.isNotBlank() }
        }

        if (icons.isEmpty()) findHtmlLinkIcons(doc, icons)

        if (icons.isEmpty()) {
            val origin = URL(pageUrl).let { "${it.protocol}://${it.host}" }
            FALLBACK_PATHS.firstOrNull { isUrlReachable("$origin$it") }
                ?.let { icons[0] = "$origin$it" }
        }

        val icon = icons.lastEntry()?.value?.let { downloadBitmap(it) }
        return Pair(title, icon)
    }

    private fun parseManifest(manifestUrl: String, icons: TreeMap<Int, String>): String? {
        return try {
            val conn = URL(manifestUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val arr = json.optJSONArray("icons")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val src = obj.optString("src").takeIf { it.isNotEmpty() } ?: continue
                    if (src.endsWith(".svg")) continue
                    var width = getWidthFromIcon(obj.optString("sizes", ""))
                    if (obj.optString("purpose", "").contains("maskable")) width += 20000
                    icons[width] = URL(URL(manifestUrl), src).toString()
                }
            }

            json.optString("name").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun findHtmlLinkIcons(doc: org.jsoup.nodes.Document, icons: TreeMap<Int, String>) {
        for (el in doc.select("link[rel*=icon]")) {
            val href = el.absUrl("href").takeIf { it.isNotEmpty() } ?: continue
            if (SUPPORTED_EXTENSIONS.none { href.lowercase().endsWith(it) }) continue
            val sizes = el.attr("sizes")
            var width = if (sizes.isNotEmpty()) getWidthFromIcon(sizes) else 1
            if (el.attr("rel").lowercase().contains("apple-touch-icon")) width += 10000
            icons[width] = href
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", Const.DESKTOP_USER_AGENT)
            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp != null && bmp.width >= Const.FAVICON_MIN_WIDTH) bmp else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isUrlReachable(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun finish(title: String?, icon: Bitmap?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeoutRunnable)
        webView.stopLoading()
        webView.destroy()
        onResult(title, icon)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private val SUPPORTED_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".ico", ".webp")
        private val FALLBACK_PATHS = listOf(
            "/favicon.ico", "/favicon.png",
            "/assets/img/favicon.png", "/assets/favicon.png",
            "/static/favicon.png", "/images/favicon.png",
        )
    }
}
