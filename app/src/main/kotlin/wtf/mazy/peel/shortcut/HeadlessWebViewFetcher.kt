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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.ShortcutIconUtils.getWidthFromIcon
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.buildUserAgent
import java.net.HttpURLConnection
import java.net.URL

data class FetchCandidate(val title: String?, val icon: Bitmap?, val source: String)

class HeadlessWebViewFetcher(
    context: Context,
    private val url: String,
    private val settings: WebAppSettings,
    private val onProgress: ((String) -> Unit)? = null,
    private val onResult: (List<FetchCandidate>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val webView = WebView(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var customHeaders: Map<String, String> = emptyMap()
    private lateinit var userAgent: String
    private var extracting = false
    private var finished = false
    private val timeoutRunnable = Runnable { tryExtract(force = true) }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        configureWebView()
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        postProgress(appContext.getString(R.string.fetch_step_loading))

        var loadUrl = url
        if (loadUrl.startsWith("http://") && settings.isAlwaysHttps == true)
            loadUrl = loadUrl.replaceFirst("http://", "https://")
        webView.loadUrl(loadUrl, customHeaders)
    }

    fun cancel() {
        finish()
    }

    private fun configureWebView() {
        webView.buildUserAgent()
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
            if (key.equals("User-Agent", ignoreCase = true)) webView.settings.userAgentString = value
            else headers[key] = value
        }
        customHeaders = headers
        userAgent = webView.settings.userAgentString

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view != null) tryExtract(force = true)
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) finish()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) finish()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view != null && newProgress >= EARLY_EXTRACT_PROGRESS) tryExtract(force = false)
            }
        }
    }

    private fun tryExtract(force: Boolean) {
        if (finished || extracting) return
        val view = webView
        extracting = true
        view.evaluateJavascript(EXTRACT_JS) { raw ->
            if (finished) return@evaluateJavascript
            val json = parseJsResult(raw)
            val ready = json?.optBoolean("ready") == true
            val hasLinks = json != null && (
                (json.optJSONArray("iconLinks")?.length() ?: 0) > 0 ||
                json.optString("manifestUrl").isNotEmpty()
            )
            if ((!ready || !hasLinks) && !force) {
                extracting = false
                return@evaluateJavascript
            }
            handler.removeCallbacksAndMessages(null)
            scope.launch { finish(resolve(raw, view.url ?: url)) }
        }
    }

    private suspend fun resolve(raw: String?, pageUrl: String): List<FetchCandidate> =
        withContext(Dispatchers.IO) {
            val json = parseJsResult(raw)
            val pageTitle = json?.optString("title")?.takeIf { it.isNotEmpty() }

            val candidates = mutableListOf<FetchCandidate>()

            if (json != null) {
                scope.ensureActive()
                postProgress(appContext.getString(R.string.fetch_step_manifests))
                findManifestCandidate(json, pageUrl, pageTitle)?.let { candidates += it }

                scope.ensureActive()
                postProgress(appContext.getString(R.string.fetch_step_icons))
                candidates += findPageIconCandidates(json, pageTitle)
            }

            scope.ensureActive()
            postProgress(appContext.getString(R.string.fetch_step_downloading))
            findFaviconCandidate(pageUrl, pageTitle)?.let { candidates += it }

            val bestTitle = candidates.firstOrNull { it.source == "PWA" }?.title ?: pageTitle
            if (bestTitle != null) candidates += FetchCandidate(bestTitle, null, "")

            candidates.deduplicatedBySize()
        }

    private fun findManifestCandidate(
        json: JSONObject, pageUrl: String, pageTitle: String?,
    ): FetchCandidate? {
        val jsManifestUrl = json.optString("manifestUrl").takeIf { it.isNotEmpty() }
        if (jsManifestUrl != null) {
            tryManifest(jsManifestUrl, pageTitle)?.let { return it }
        }
        val origins = linkedSetOf(originOf(pageUrl), originOf(url))
        for (origin in origins) {
            for (path in MANIFEST_FALLBACK_PATHS) {
                scope.ensureActive()
                tryManifest("$origin$path", pageTitle)?.let { return it }
            }
        }
        return null
    }

    private fun tryManifest(manifestUrl: String, pageTitle: String?): FetchCandidate? {
        return try {
            val conn = openConnection(manifestUrl)
            try {
                if (conn.responseCode != 200) return null
                val manifest = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val name = manifest.optString("name").takeIf { it.isNotEmpty() }
                val icon = downloadBestManifestIcon(manifest, manifestUrl)
                if (name != null || icon != null) FetchCandidate(name ?: pageTitle, icon, "PWA")
                else null
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { null }
    }

    private fun downloadBestManifestIcon(manifest: JSONObject, baseUrl: String): Bitmap? {
        val arr = manifest.optJSONArray("icons") ?: return null
        val any = mutableListOf<Pair<Int, String>>()
        val other = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val src = obj.optString("src").takeIf { it.isNotEmpty() } ?: continue
            if (src.endsWith(".svg")) continue
            val width = getWidthFromIcon(obj.optString("sizes", ""))
            val resolved = URL(URL(baseUrl), src).toString()
            val purpose = obj.optString("purpose").lowercase()
            if (purpose.isEmpty() || purpose.contains("any")) any += width to resolved
            else other += width to resolved
        }
        return downloadLargest(any) ?: downloadLargest(other)
    }

    private fun findPageIconCandidates(
        json: JSONObject, pageTitle: String?,
    ): List<FetchCandidate> {
        val arr = json.optJSONArray("iconLinks") ?: return emptyList()
        val touchIcons = mutableListOf<Pair<Int, String>>()
        val linkIcons = mutableListOf<Pair<Int, String>>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val href = obj.optString("href").takeIf { it.isNotEmpty() } ?: continue
            val path = try { URL(href).path.lowercase() } catch (_: Exception) { href.lowercase() }
            if (ICON_EXTENSIONS.none { path.endsWith(it) }) continue
            val sizes = obj.optString("sizes")
            val width = if (sizes.isNotEmpty()) getWidthFromIcon(sizes) else 1
            val rel = obj.optString("rel").lowercase()
            if (rel.contains("apple-touch-icon")) touchIcons += width to href
            else linkIcons += width to href
        }

        val result = mutableListOf<FetchCandidate>()
        downloadLargest(touchIcons)?.let { result += FetchCandidate(pageTitle, it, "Apple") }
        downloadLargest(linkIcons)?.let { result += FetchCandidate(pageTitle, it, "HTML") }
        return result
    }

    private fun findFaviconCandidate(pageUrl: String, pageTitle: String?): FetchCandidate? {
        val bmp = downloadBitmap("${originOf(pageUrl)}/favicon.ico") ?: return null
        return FetchCandidate(pageTitle, bmp, "Favicon")
    }

    private fun downloadLargest(icons: List<Pair<Int, String>>): Bitmap? {
        for ((_, iconUrl) in icons.sortedByDescending { it.first }) {
            scope.ensureActive()
            downloadBitmap(iconUrl)?.let { return it }
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
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null && bmp.width >= MIN_ICON_WIDTH) bmp else null
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { null }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECTION_TIMEOUT_MS
        conn.readTimeout = CONNECTION_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies != null) conn.setRequestProperty("Cookie", cookies)
        customHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return conn
    }

    private fun postProgress(text: String) {
        handler.post { onProgress?.invoke(text) }
    }

    private fun finish(candidates: List<FetchCandidate> = emptyList()) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        webView.stopLoading()
        webView.destroy()
        onResult(candidates)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val EARLY_EXTRACT_PROGRESS = 30
        private const val CONNECTION_TIMEOUT_MS = 4_000
        private const val MIN_ICON_WIDTH = 32
        private const val MAX_ICON_BYTES = 5 * 1024 * 1024
        private val ICON_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".ico", ".webp")
        private val MANIFEST_FALLBACK_PATHS =
            listOf("/manifest.webmanifest", "/manifest.json", "/site.webmanifest")

        private fun originOf(url: String): String =
            URL(url).let {
                if (it.port != -1 && it.port != it.defaultPort)
                    "${it.protocol}://${it.host}:${it.port}"
                else "${it.protocol}://${it.host}"
            }

        private fun List<FetchCandidate>.deduplicatedBySize(): List<FetchCandidate> {
            val seen = mutableSetOf<Pair<Int, Int>>()
            return filter { it.icon == null || seen.add(it.icon.width to it.icon.height) }
                .sortedBy { if (it.icon != null) 0 else 1 }
        }

        private fun parseJsResult(raw: String?): JSONObject? {
            if (raw == null || raw == "null") return null
            return try {
                val decoded = JSONArray("[$raw]").optString(0)
                if (decoded.isEmpty()) null else JSONObject(decoded)
            } catch (_: Exception) { null }
        }

        private val EXTRACT_JS = """
            (function() {
                var result = {
                    ready: document.readyState !== 'loading',
                    title: document.title || '',
                    manifestUrl: '',
                    iconLinks: []
                };
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
