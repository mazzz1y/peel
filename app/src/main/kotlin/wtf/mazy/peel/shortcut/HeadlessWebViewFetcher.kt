package wtf.mazy.peel.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.ShortcutIconUtils.getWidthFromIcon
import wtf.mazy.peel.util.Const
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class FetchCandidate(
    val title: String?,
    val icon: Bitmap?,
    val source: String,
    val startUrl: String? = null,
)

data class FetchResult(val candidates: List<FetchCandidate>, val redirectedUrl: String?)

class HeadlessWebViewFetcher(
    context: Context,
    private val url: String,
    private val settings: WebAppSettings,
    private val onProgress: ((String) -> Unit)? = null,
    private val onResult: (FetchResult) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var customHeaders: Map<String, String> = emptyMap()
    private var userAgent: String = DEFAULT_USER_AGENT
    private var finished = false
    private var resolveJob: Job? = null

    fun start() {
        val headers = mutableMapOf<String, String>()
        settings.customHeaders?.forEach { (key, value) ->
            if (key.equals("User-Agent", ignoreCase = true)) userAgent = value
            else headers[key] = value
        }
        if (settings.isRequestDesktop == true) userAgent = Const.DESKTOP_USER_AGENT
        customHeaders = headers

        postProgress(appContext.getString(R.string.fetch_step_loading))

        var loadUrl = url
        if (loadUrl.startsWith("http://") && settings.isAlwaysHttps == true)
            loadUrl = loadUrl.replaceFirst("http://", "https://")

        resolveJob = scope.launch {
            val result = try {
                withTimeout(TIMEOUT_MS) { fetchAndResolve(loadUrl) }
            } catch (_: TimeoutCancellationException) {
                FetchResult(emptyList(), null)
            } catch (_: Exception) {
                FetchResult(emptyList(), null)
            }
            finish(result)
        }
    }

    fun cancel() {
        finish()
    }

    private suspend fun fetchAndResolve(loadUrl: String): FetchResult = withContext(Dispatchers.IO) {
        val conn = openConnection(loadUrl)
        try {
            if (conn.responseCode != 200) return@withContext FetchResult(emptyList(), null)
            val finalUrl = conn.url.toString()
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val redirected = if (finalUrl.trimEnd('/') != url.trimEnd('/')) finalUrl else null
            resolve(html, finalUrl, redirected)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun resolve(html: String, pageUrl: String, redirected: String?): FetchResult =
        withContext(Dispatchers.IO) {
            val pageTitle = extractTitle(html)
            val manifestUrl = extractManifestUrl(html, pageUrl)
            val iconLinks = extractIconLinks(html, pageUrl)

            val candidates = mutableListOf<FetchCandidate>()

            scope.ensureActive()
            postProgress(appContext.getString(R.string.fetch_step_downloading))

            coroutineScope {
                val manifestDeferred = async {
                    findManifestCandidate(manifestUrl, pageUrl, pageTitle)
                }
                val pageIconsDeferred = async {
                    findPageIconCandidates(iconLinks, pageTitle)
                }
                val faviconDeferred = async {
                    findFaviconCandidate(pageUrl, pageTitle)
                }

                manifestDeferred.await()?.let { candidates += it }
                candidates += pageIconsDeferred.await()
                faviconDeferred.await()?.let { candidates += it }
            }

            val bestTitle = candidates.firstOrNull { it.source == "PWA" }?.title ?: pageTitle
            if (bestTitle != null) candidates += FetchCandidate(bestTitle, null, "")

            FetchResult(candidates.deduplicatedBySize(), redirected)
        }

    private fun findManifestCandidate(
        manifestUrl: String?, pageUrl: String, pageTitle: String?,
    ): FetchCandidate? {
        if (manifestUrl != null) {
            tryManifest(manifestUrl, pageTitle)?.let { return it }
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
                val finalUrl = conn.url.toString()
                val manifest = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val name = manifest.optString("name").takeIf { it.isNotEmpty() }
                val icon = downloadBestManifestIcon(manifest, finalUrl)
                val startUrl = manifest.optString("start_url").takeIf { it.isNotEmpty() }
                    ?.let { URL(URL(finalUrl), it).toString() }
                if (name != null || icon != null) FetchCandidate(
                    name ?: pageTitle,
                    icon,
                    "PWA",
                    startUrl
                )
                else null
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
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
        iconLinks: List<IconLink>, pageTitle: String?,
    ): List<FetchCandidate> {
        val touchIcons = mutableListOf<Pair<Int, String>>()
        val linkIcons = mutableListOf<Pair<Int, String>>()

        for (link in iconLinks) {
            val width = if (link.sizes.isNotEmpty()) getWidthFromIcon(link.sizes) else 1
            if (link.rel.contains("apple-touch-icon")) touchIcons += width to link.href
            else linkIcons += width to link.href
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
                val contentType = conn.contentType?.lowercase().orEmpty()
                if (contentType.contains("image/svg")) return null
                if (
                    contentType.startsWith("text/") ||
                    contentType.contains("html") ||
                    contentType.contains("json") ||
                    contentType.contains("xml")
                ) return null
                if (conn.contentLength > MAX_ICON_BYTES) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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
        customHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return conn
    }

    private fun postProgress(text: String) {
        handler.post { onProgress?.invoke(text) }
    }

    private fun finish(result: FetchResult = FetchResult(emptyList(), null)) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        onResult(result)
    }

    private data class IconLink(val href: String, val rel: String, val sizes: String)

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val CONNECTION_TIMEOUT_MS = 4_000
        private const val MAX_ICON_BYTES = 5 * 1024 * 1024
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
        private val MANIFEST_FALLBACK_PATHS =
            listOf("/manifest.webmanifest", "/manifest.json", "/site.webmanifest")

        private val TITLE_RE = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
        private val MANIFEST_RE = Pattern.compile("""<link[^>]+rel\s*=\s*["']manifest["'][^>]+href\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        private val MANIFEST_RE2 = Pattern.compile("""<link[^>]+href\s*=\s*["']([^"']+)["'][^>]+rel\s*=\s*["']manifest["']""", Pattern.CASE_INSENSITIVE)
        private val ICON_LINK_RE = Pattern.compile("""<link\b([^>]*rel\s*=\s*["'][^"']*icon[^"']*["'][^>]*)>""", Pattern.CASE_INSENSITIVE)
        private val HREF_RE = Pattern.compile("""href\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        private val REL_RE = Pattern.compile("""rel\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        private val SIZES_RE = Pattern.compile("""sizes\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)

        private fun extractTitle(html: String): String? {
            val m = TITLE_RE.matcher(html)
            return if (m.find()) m.group(1)?.trim()?.takeIf { it.isNotEmpty() } else null
        }

        private fun extractManifestUrl(html: String, baseUrl: String): String? {
            for (re in listOf(MANIFEST_RE, MANIFEST_RE2)) {
                val m = re.matcher(html)
                if (m.find()) {
                    val href = m.group(1) ?: continue
                    return try { URL(URL(baseUrl), href).toString() } catch (_: Exception) { href }
                }
            }
            return null
        }

        private fun extractIconLinks(html: String, baseUrl: String): List<IconLink> {
            val result = mutableListOf<IconLink>()
            val m = ICON_LINK_RE.matcher(html)
            while (m.find()) {
                val attrs = m.group(1) ?: continue
                val hrefMatch = HREF_RE.matcher(attrs)
                if (!hrefMatch.find()) continue
                val href = try { URL(URL(baseUrl), hrefMatch.group(1)).toString() } catch (_: Exception) { continue }
                val relMatch = REL_RE.matcher(attrs)
                val rel = if (relMatch.find()) relMatch.group(1)?.lowercase() ?: "" else ""
                val sizesMatch = SIZES_RE.matcher(attrs)
                val sizes = if (sizesMatch.find()) sizesMatch.group(1) ?: "" else ""
                result.add(IconLink(href, rel, sizes))
            }
            return result
        }

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
    }
}
