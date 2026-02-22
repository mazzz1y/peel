package wtf.mazy.peel.shortcut

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.ShortcutIconUtils.getWidthFromIcon
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.sanitizeUserAgent
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

class HeadlessWebViewFetcher(
    context: Context,
    private val url: String,
    private val settings: WebAppSettings,
    private val onResult: (title: String?, icon: Bitmap?) -> Unit,
) {
    private val webView = WebView(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var customHeaders: Map<String, String> = emptyMap()
    private var finished = false
    private var extracting = false
    private val timeoutRunnable = Runnable { finish(null, null) }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        webView.sanitizeUserAgent(this)

        webView.settings.apply {
            javaScriptEnabled = settings.isAllowJs == true
            blockNetworkImage = true
            domStorageEnabled = true
            safeBrowsingEnabled = settings.isSafeBrowsing == true
            if (settings.isRequestDesktop == true) {
                userAgentString = Const.DESKTOP_USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(settings.isAllowCookies == true)
            setAcceptThirdPartyCookies(webView, settings.isAllowThirdPartyCookies == true)
        }

        val headers = mutableMapOf<String, String>()
        settings.customHeaders?.forEach { (key, value) ->
            if (key.equals("User-Agent", ignoreCase = true)) {
                webView.settings.userAgentString = value
            } else {
                headers[key] = value
            }
        }
        customHeaders = headers

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) finish(null, null)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) finish(null, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                if (finished || extracting) return
                if (progress >= EARLY_EXTRACT_PROGRESS) tryExtract(view)
            }
        }

        var loadUrl = url
        if (loadUrl.startsWith("http://") && settings.isAlwaysHttps == true) {
            loadUrl = loadUrl.replaceFirst("http://", "https://")
        }

        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        webView.loadUrl(loadUrl, customHeaders)
    }

    private fun tryExtract(view: WebView) {
        if (finished || extracting) return
        extracting = true

        view.evaluateJavascript(FIND_LINKS_JS) { raw ->
            if (finished) return@evaluateJavascript
            scope.launch {
                val result = resolve(raw, view.url ?: url)
                if (result.first != null || result.second != null) {
                    finish(result.first, result.second)
                } else {
                    extracting = false
                }
            }
        }
    }

    private suspend fun resolve(raw: String?, pageUrl: String): Pair<String?, Bitmap?> =
        withContext(Dispatchers.IO) {
            if (raw == null || raw == "null") return@withContext Pair(null, null)

            try {
                val decoded = JSONArray("[$raw]").optString(0)
                if (decoded.isEmpty()) return@withContext Pair(null, null)
                val json = JSONObject(decoded)

                var title: String? = null
                val icons = TreeMap<Int, String>()

                ensureActive()
                val manifestUrl = json.optString("manifestUrl").takeIf { it.isNotEmpty() }
                val tried = mutableSetOf<String>()
                if (manifestUrl != null) {
                    tried.add(manifestUrl)
                    title = parseManifest(manifestUrl, icons)
                }

                val origin = originOf(url)
                for (path in MANIFEST_PATHS) {
                    val candidate = "$origin$path"
                    if (tried.add(candidate)) {
                        val name = parseManifest(candidate, icons)
                        if (title == null && name != null) title = name
                        if (icons.isNotEmpty()) break
                    }
                }

                if (title == null) {
                    title = json.optString("title").takeIf { it.isNotEmpty() }
                }

                ensureActive()
                val iconLinks = json.optJSONArray("iconLinks")
                if (iconLinks != null) parseIconLinks(iconLinks, icons)

                ensureActive()
                if (icons.isEmpty()) {
                    val pageOrigin = originOf(pageUrl)
                    if (isReachable("$pageOrigin/favicon.ico")) icons[0] = "$pageOrigin/favicon.ico"
                }

                ensureActive()
                val icon = downloadBestIcon(icons)
                Pair(title, icon)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                Pair(null, null)
            }
        }

    private fun parseManifest(manifestUrl: String, icons: TreeMap<Int, String>): String? {
        return try {
            val conn = openConnection(manifestUrl)
            try {
                if (conn.responseCode != 200) return null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

                val arr = json.optJSONArray("icons")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val src = obj.optString("src").takeIf { it.isNotEmpty() } ?: continue
                        if (src.endsWith(".svg")) continue
                        var width = getWidthFromIcon(obj.optString("sizes", ""))
                        if (obj.optString("purpose", "").contains("maskable")) width += 20000
                        width += MANIFEST_ICON_BOOST
                        icons[width] = URL(URL(manifestUrl), src).toString()
                    }
                }

                json.optString("name").takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
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

    private suspend fun downloadBestIcon(icons: TreeMap<Int, String>): Bitmap? {
        for (entry in icons.descendingMap()) {
            currentCoroutineContext().ensureActive()
            val bmp = downloadBitmap(entry.value)
            if (bmp != null) return bmp
        }
        return null
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = openConnection(url)
            try {
                if (conn.responseCode != 200) return null
                if (conn.contentLength > MAX_ICON_BYTES) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                val opts =
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null && bmp.width >= MIN_ICON_WIDTH) bmp else null
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isReachable(url: String): Boolean {
        return try {
            val conn = openConnection(url)
            try {
                conn.requestMethod = "HEAD"
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECTION_TIMEOUT_MS
        conn.readTimeout = CONNECTION_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies != null) conn.setRequestProperty("Cookie", cookies)
        customHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return conn
    }

    private fun finish(title: String?, icon: Bitmap?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeoutRunnable)
        scope.cancel()
        webView.stopLoading()
        webView.destroy()
        onResult(title, icon)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val CONNECTION_TIMEOUT_MS = 4_000
        private const val EARLY_EXTRACT_PROGRESS = 30
        private const val MIN_ICON_WIDTH = 32
        private const val MANIFEST_ICON_BOOST = 30000
        private const val MAX_ICON_BYTES = 5 * 1024 * 1024
        private val SUPPORTED_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".ico", ".webp")
        private val MANIFEST_PATHS = listOf(
            "/manifest.webmanifest", "/manifest.json", "/site.webmanifest"
        )

        private fun originOf(url: String): String =
            URL(url).let {
                if (it.port != -1 && it.port != it.defaultPort) "${it.protocol}://${it.host}:${it.port}"
                else "${it.protocol}://${it.host}"
            }

        private val FIND_LINKS_JS =
            """
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
