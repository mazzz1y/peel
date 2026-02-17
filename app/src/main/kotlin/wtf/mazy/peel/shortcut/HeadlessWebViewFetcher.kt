package wtf.mazy.peel.shortcut

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject
import wtf.mazy.peel.shortcut.ShortcutIconUtils.getWidthFromIcon

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
                view.evaluateJavascript(FIND_LINKS_JS) { raw ->
                    Thread {
                        val result = resolve(raw, loadedUrl)
                        handler.post { finish(result.first, result.second) }
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

    private fun resolve(raw: String?, pageUrl: String): Pair<String?, Bitmap?> {
        if (raw == null || raw == "null") return Pair(null, null)

        return try {
            val decoded = JSONArray("[$raw]").optString(0)
            if (decoded.isEmpty()) return Pair(null, null)
            val json = JSONObject(decoded)

            var title: String? = null
            val icons = TreeMap<Int, String>()

            val manifestUrl = json.optString("manifestUrl").takeIf { it.isNotEmpty() }
            if (manifestUrl != null) {
                title = parseManifest(manifestUrl, icons)
            }

            if (title == null) {
                title = json.optString("title").takeIf { it.isNotEmpty() }
            }

            if (icons.isEmpty()) {
                val iconLinks = json.optJSONArray("iconLinks")
                if (iconLinks != null) parseIconLinks(iconLinks, icons)
            }

            if (icons.isEmpty()) {
                val origin = URL(pageUrl).let { "${it.protocol}://${it.host}" }
                FALLBACK_PATHS.firstOrNull { isReachable("$origin$it") }
                    ?.let { icons[0] = "$origin$it" }
            }

            val icon = icons.lastEntry()?.value?.let { downloadBitmap(it) }
            Pair(title, icon)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun parseManifest(manifestUrl: String, icons: TreeMap<Int, String>): String? {
        return try {
            val conn = openConnection(manifestUrl)
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

    private fun parseIconLinks(arr: JSONArray, icons: TreeMap<Int, String>) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val href = obj.optString("href").takeIf { it.isNotEmpty() } ?: continue
            if (SUPPORTED_EXTENSIONS.none { href.lowercase().endsWith(it) }) continue
            val sizes = obj.optString("sizes")
            var width = if (sizes.isNotEmpty()) getWidthFromIcon(sizes) else 1
            if (obj.optString("rel").lowercase().contains("apple-touch-icon")) width += 10000
            icons[width] = href
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = openConnection(url)
            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp != null && bmp.width >= MIN_ICON_WIDTH) bmp else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isReachable(url: String): Boolean {
        return try {
            val conn = openConnection(url)
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies != null) conn.setRequestProperty("Cookie", cookies)
        return conn
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
        private const val MIN_ICON_WIDTH = 48
        private val SUPPORTED_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".ico", ".webp")
        private val FALLBACK_PATHS = listOf(
            "/favicon.ico", "/favicon.png",
            "/assets/img/favicon.png", "/assets/favicon.png",
            "/static/favicon.png", "/images/favicon.png",
        )

        private val FIND_LINKS_JS = """
        (function() {
            var result = { title: document.title || '', manifestUrl: '', iconLinks: [] };
            var manifest = document.querySelector('link[rel="manifest"]');
            if (manifest && manifest.href) result.manifestUrl = manifest.href;
            var links = document.querySelectorAll('link[rel*="icon"]');
            for (var i = 0; i < links.length; i++) {
                var l = links[i];
                if (l.href) result.iconLinks.push({
                    href: l.href, rel: l.rel || '', sizes: l.getAttribute('sizes') || ''
                });
            }
            return JSON.stringify(result);
        })();
        """.trimIndent()
    }
}
