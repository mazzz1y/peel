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
import java.util.TreeMap

data class FetchCandidate(
    val title: String?,
    val icon: Bitmap?,
    val source: String,
)

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
    private var extractStarted = false

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
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
            if (key.equals("User-Agent", ignoreCase = true)) {
                webView.settings.userAgentString = value
            } else {
                headers[key] = value
            }
        }
        customHeaders = headers
        userAgent = webView.settings.userAgentString

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view != null) extract(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
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

        webView.webChromeClient = object : WebChromeClient() {}

        var loadUrl = url
        if (loadUrl.startsWith("http://") && settings.isAlwaysHttps == true) {
            loadUrl = loadUrl.replaceFirst("http://", "https://")
        }

        handler.postDelayed({ finish() }, TIMEOUT_MS)
        postProgress(appContext.getString(R.string.fetch_step_loading))
        webView.loadUrl(loadUrl, customHeaders)
    }

    private fun extract(view: WebView) {
        if (extractStarted) return
        extractStarted = true
        handler.removeCallbacksAndMessages(null)

        view.evaluateJavascript(FIND_LINKS_JS) { raw ->
            scope.launch {
                finish(resolve(raw, view.url ?: url))
            }
        }
    }

    private suspend fun resolve(raw: String?, pageUrl: String): List<FetchCandidate> =
        withContext(Dispatchers.IO) {
            val json = parseJsResult(raw) ?: return@withContext emptyList()

            val candidates = mutableListOf<FetchCandidate>()
            val pageTitle = json.optString("title").takeIf { it.isNotEmpty() }

            scope.ensureActive()
            postProgress(appContext.getString(R.string.fetch_step_manifests))
            collectManifest(json, pageTitle, candidates)
            if (candidates.none { it.icon != null }) {
                collectManifestByGuess(pageUrl, pageTitle, candidates)
            }

            scope.ensureActive()
            postProgress(appContext.getString(R.string.fetch_step_icons))
            collectPageIcons(json, pageTitle, candidates)

            scope.ensureActive()
            postProgress(appContext.getString(R.string.fetch_step_downloading))
            collectFavicon(pageUrl, pageTitle, candidates)

            val bestTitle = candidates.firstOrNull { it.source == "PWA" }?.title ?: pageTitle
            if (bestTitle != null) {
                candidates.add(FetchCandidate(bestTitle, null, "Fallback"))
            }

            val seen = mutableSetOf<Pair<Int, Int>>()
            candidates.filter { it.icon == null || seen.add(it.icon.width to it.icon.height) }
                .sortedBy { if (it.icon != null) 0 else 1 }
        }

    private fun parseJsResult(raw: String?): JSONObject? {
        if (raw == null || raw == "null") return null
        return try {
            val decoded = JSONArray("[$raw]").optString(0)
            if (decoded.isEmpty()) null else JSONObject(decoded)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun collectManifest(
        json: JSONObject,
        pageTitle: String?,
        out: MutableList<FetchCandidate>,
    ) {
        val manifestUrl = json.optString("manifestUrl").takeIf { it.isNotEmpty() } ?: return
        try {
            val conn = openConnection(manifestUrl)
            try {
                if (conn.responseCode != 200) return
                val manifest = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val name = manifest.optString("name").takeIf { it.isNotEmpty() }
                val icon = downloadBestManifestIcon(manifest, manifestUrl)
                if (name != null || icon != null) {
                    out.add(FetchCandidate(name ?: pageTitle, icon, "PWA"))
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun collectManifestByGuess(
        pageUrl: String,
        pageTitle: String?,
        out: MutableList<FetchCandidate>,
    ) {
        val origin = originOf(pageUrl)
        for (path in MANIFEST_FALLBACK_PATHS) {
            scope.ensureActive()
            try {
                val conn = openConnection("$origin$path")
                try {
                    if (conn.responseCode != 200) continue
                    val manifest =
                        JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val name = manifest.optString("name").takeIf { it.isNotEmpty() }
                    val icon = downloadBestManifestIcon(manifest, "$origin$path")
                    if (name != null || icon != null) {
                        out.add(FetchCandidate(name ?: pageTitle, icon, "PWA"))
                        return
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun downloadBestManifestIcon(manifest: JSONObject, baseUrl: String): Bitmap? {
        val icons = TreeMap<Int, String>()
        val arr = manifest.optJSONArray("icons") ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val src = obj.optString("src").takeIf { it.isNotEmpty() } ?: continue
            if (src.endsWith(".svg")) continue
            icons[getWidthFromIcon(obj.optString("sizes", ""))] = URL(URL(baseUrl), src).toString()
        }
        return downloadBestIcon(icons)
    }

    private suspend fun collectPageIcons(
        json: JSONObject,
        pageTitle: String?,
        out: MutableList<FetchCandidate>,
    ) {
        val arr = json.optJSONArray("iconLinks") ?: return
        val touchIcons = TreeMap<Int, String>()
        val linkIcons = TreeMap<Int, String>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val href = obj.optString("href").takeIf { it.isNotEmpty() } ?: continue
            if (SUPPORTED_EXTENSIONS.none { href.lowercase().endsWith(it) }) continue
            val sizes = obj.optString("sizes")
            val width = if (sizes.isNotEmpty()) getWidthFromIcon(sizes) else 1
            val rel = obj.optString("rel").lowercase()
            if (rel.contains("apple-touch-icon")) touchIcons[width] = href
            else linkIcons[width] = href
        }

        val touchIcon = downloadBestIcon(touchIcons)
        if (touchIcon != null) out.add(FetchCandidate(pageTitle, touchIcon, "Apple"))
        val linkIcon = downloadBestIcon(linkIcons)
        if (linkIcon != null) out.add(FetchCandidate(pageTitle, linkIcon, "HTML"))
    }

    private fun collectFavicon(
        pageUrl: String,
        pageTitle: String?,
        out: MutableList<FetchCandidate>,
    ) {
        val favicon = downloadBitmap("${originOf(pageUrl)}/favicon.ico")
        if (favicon != null) out.add(FetchCandidate(pageTitle, favicon, "Favicon"))
    }

    private fun downloadBestIcon(icons: TreeMap<Int, String>): Bitmap? {
        for (entry in icons.descendingMap()) {
            scope.ensureActive()
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
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        webView.stopLoading()
        webView.destroy()
        onResult(candidates)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val CONNECTION_TIMEOUT_MS = 4_000
        private const val MIN_ICON_WIDTH = 32
        private const val MAX_ICON_BYTES = 5 * 1024 * 1024
        private val SUPPORTED_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".ico", ".webp")
        private val MANIFEST_FALLBACK_PATHS = listOf(
            "/manifest.webmanifest", "/manifest.json", "/site.webmanifest"
        )

        private fun originOf(url: String): String = URL(url).let {
            if (it.port != -1 && it.port != it.defaultPort) "${it.protocol}://${it.host}:${it.port}"
            else "${it.protocol}://${it.host}"
        }

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
