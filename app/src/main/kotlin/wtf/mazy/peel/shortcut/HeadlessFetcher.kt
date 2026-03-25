package wtf.mazy.peel.shortcut

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.ShortcutIconUtils.getWidthFromIcon
import java.net.HttpURLConnection
import java.net.URL

data class FetchCandidate(
    val title: String?,
    val icon: Bitmap?,
    val source: String,
    val startUrl: String? = null,
)

data class FetchResult(val candidates: List<FetchCandidate>, val redirectedUrl: String?)

class HeadlessFetcher(
    private val activity: Activity,
    private val url: String,
    private val settings: WebAppSettings,
    private val contextId: String?,
    private val usePrivateMode: Boolean,
    private val onProgress: ((String) -> Unit)? = null,
    private val onResult: (FetchResult) -> Unit,
) {
    private val appContext = activity.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var customHeaders: Map<String, String> = emptyMap()
    private var userAgent: String = GeckoSession.getDefaultUserAgent()
    private var desktopMode = false
    private var finished = false
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null

    fun start() {
        val headers = mutableMapOf<String, String>()
        settings.customHeaders?.forEach { (key, value) ->
            if (!key.equals("User-Agent", ignoreCase = true)) headers[key] = value
        }
        desktopMode = settings.isRequestDesktop == true
        customHeaders = headers

        var loadUrl = url
        if (loadUrl.startsWith("http://") && settings.isAlwaysHttps == true)
            loadUrl = loadUrl.replaceFirst("http://", "https://")

        scope.launch {
            val result = try {
                withTimeout(TIMEOUT_MS) { doFetch(loadUrl) }
            } catch (_: TimeoutCancellationException) {
                FetchResult(emptyList(), null)
            } catch (_: Exception) {
                FetchResult(emptyList(), null)
            } finally {
                teardown()
            }
            finish(result)
        }
    }

    fun cancel() {
        teardown()
        finish()
    }

    private suspend fun doFetch(loadUrl: String): FetchResult {
        val pageLoaded = CompletableDeferred<Unit>()
        val jsData = CompletableDeferred<JSONObject>()
        var capturedUrl: String? = null
        var capturedTitle: String? = null

        val ext = GeckoRuntimeProvider.ensurePageInfoExtension(appContext)

        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .apply {
                    if (desktopMode) {
                        userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                        viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                    }
                    if (contextId != null) contextId(contextId)
                    usePrivateMode(usePrivateMode)
                }
                .build()
        )
        geckoSession = session

        if (ext != null) {
            session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onMessage(
                        nativeApp: String,
                        message: Any,
                        sender: WebExtension.MessageSender,
                    ): GeckoResult<Any>? {
                        if (message is JSONObject) jsData.complete(message)
                        return null
                    }
                },
                GeckoRuntimeProvider.PAGE_INFO_APP,
            )
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (url != null && !url.startsWith("about:")) capturedUrl = url
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (capturedUrl != null) pageLoaded.complete(Unit)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrEmpty()) capturedTitle = title
            }
        }

        postProgress(appContext.getString(R.string.fetch_step_loading))
        session.open(GeckoRuntimeProvider.getRuntime(appContext))
        attachView(session)
        session.setActive(true)

        if (customHeaders.isNotEmpty()) {
            session.load(
                GeckoSession.Loader()
                    .uri(loadUrl)
                    .additionalHeaders(customHeaders)
                    .headerFilter(GeckoSession.HEADER_FILTER_UNRESTRICTED_UNSAFE)
            )
        } else {
            session.loadUri(loadUrl)
        }

        pageLoaded.await()

        val info = withTimeoutOrNull(JS_WAIT_MS) { jsData.await() }

        postProgress(appContext.getString(R.string.fetch_step_downloading))

        val pageUrl = capturedUrl ?: loadUrl
        val redirected = if (pageUrl.trimEnd('/') != url.trimEnd('/')) pageUrl else null

        return withContext(Dispatchers.IO) {
            resolve(info, capturedTitle, pageUrl, redirected)
        }
    }

    private fun attachView(session: GeckoSession) {
        val view = GeckoView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            visibility = View.INVISIBLE
        }
        activity.findViewById<ViewGroup>(android.R.id.content).addView(view)
        geckoView = view
        view.setSession(session)
    }

    private fun resolve(
        jsInfo: JSONObject?,
        pageTitle: String?,
        pageUrl: String,
        redirected: String?,
    ): FetchResult {
        val candidates = mutableListOf<FetchCandidate>()

        val manifest = jsInfo?.optJSONObject("manifest")
        val manifestUrl = jsInfo?.optString("manifestUrl")?.takeIf { it.isNotEmpty() }
        resolveManifest(manifest, manifestUrl ?: pageUrl, pageTitle)?.let { candidates += it }

        scope.ensureActive()
        resolvePageIcons(jsInfo?.optJSONArray("iconLinks"), pageTitle)?.let { candidates += it }

        scope.ensureActive()
        resolveFavicon(pageUrl, pageTitle)?.let { candidates += it }

        val bestTitle = candidates.firstOrNull { it.source == "PWA" }?.title ?: pageTitle
        if (bestTitle != null) candidates += FetchCandidate(bestTitle, null, "")

        return FetchResult(candidates.deduplicatedBySize(), redirected)
    }

    private fun resolveManifest(
        manifest: JSONObject?,
        baseUrl: String,
        pageTitle: String?,
    ): FetchCandidate? {
        val m = manifest ?: probeManifestFallback(baseUrl) ?: return null
        val name = m.optString("name").takeIf { it.isNotEmpty() }
            ?: m.optString("short_name").takeIf { it.isNotEmpty() }
        val icon = downloadBestManifestIcon(m, baseUrl)
        val startUrl = m.optString("start_url").takeIf { it.isNotEmpty() }
            ?.let { resolveUrl(baseUrl, it) }
        if (name == null && icon == null) return null
        return FetchCandidate(name ?: pageTitle, icon, "PWA", startUrl)
    }

    private fun resolvePageIcons(
        iconLinks: JSONArray?,
        pageTitle: String?,
    ): List<FetchCandidate>? {
        if (iconLinks == null) return null
        val touchIcons = mutableListOf<Pair<Int, String>>()
        val linkIcons = mutableListOf<Pair<Int, String>>()
        for (i in 0 until iconLinks.length()) {
            val obj = iconLinks.optJSONObject(i) ?: continue
            val href = obj.optString("href").takeIf { it.isNotEmpty() } ?: continue
            val sizes = obj.optString("sizes")
            val width = if (sizes.isNotEmpty()) getWidthFromIcon(sizes) else 1
            val rel = obj.optString("rel").lowercase()
            if (rel.contains("apple-touch-icon")) touchIcons += width to href
            else linkIcons += width to href
        }
        val result = mutableListOf<FetchCandidate>()
        downloadLargest(touchIcons)?.let { result += FetchCandidate(pageTitle, it, "Apple") }
        downloadLargest(linkIcons)?.let { result += FetchCandidate(pageTitle, it, "HTML") }
        return result.ifEmpty { null }
    }

    private fun resolveFavicon(pageUrl: String, pageTitle: String?): FetchCandidate? {
        val faviconUrl = "${originOf(pageUrl)}/favicon.ico"
        val bmp = downloadBitmap(faviconUrl) ?: return null
        return FetchCandidate(pageTitle, bmp, "Favicon")
    }

    private fun probeManifestFallback(pageUrl: String): JSONObject? {
        val tried = mutableSetOf<String>()
        val bases = linkedSetOf(pageUrl, url)
        for (base in bases) {
            for (path in MANIFEST_FALLBACK_PATHS) {
                scope.ensureActive()
                val resolved = resolveUrl(base, path)
                if (tried.add(resolved)) {
                    tryFetchManifest(resolved)?.let { return it }
                }
            }
        }
        for (base in bases) {
            for (path in MANIFEST_FALLBACK_PATHS) {
                scope.ensureActive()
                val atOrigin = "${originOf(base)}$path"
                if (tried.add(atOrigin)) {
                    tryFetchManifest(atOrigin)?.let { return it }
                }
            }
        }
        return null
    }

    private fun tryFetchManifest(manifestUrl: String): JSONObject? {
        return try {
            val conn = openConnection(manifestUrl)
            try {
                val code = conn.responseCode
                if (code != 200) return null
                if (conn.contentType?.lowercase().orEmpty().contains("html")) return null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (json.has("name") || json.has("short_name") || json.has("icons")) json else null
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
            val resolved = resolveUrl(baseUrl, src)
            val purpose = obj.optString("purpose").lowercase()
            if (purpose.isEmpty() || purpose.contains("any")) any += width to resolved
            else other += width to resolved
        }
        return downloadLargest(any) ?: downloadLargest(other)
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
                val ct = conn.contentType?.lowercase().orEmpty()
                if (ct.contains("image/svg")) return null
                if (ct.startsWith("text/") || ct.contains("html") ||
                    ct.contains("json") || ct.contains("xml")
                ) return null
                if (conn.contentLength > MAX_ICON_BYTES) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
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

    private fun teardown() {
        val session = geckoSession
        val view = geckoView
        geckoSession = null
        geckoView = null
        if (session != null || view != null) {
            handler.post {
                try {
                    session?.setActive(false)
                    view?.releaseSession()
                    session?.close()
                    (view?.parent as? ViewGroup)?.removeView(view)
                } catch (_: Exception) {
                }
            }
        }
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

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val JS_WAIT_MS = 3_000L
        private const val CONNECTION_TIMEOUT_MS = 4_000
        private const val MAX_ICON_BYTES = 5 * 1024 * 1024
        private val MANIFEST_FALLBACK_PATHS =
            listOf("/manifest.json", "/manifest.webmanifest", "/site.webmanifest")

        private fun resolveUrl(base: String, relative: String): String =
            try {
                URL(URL(base), relative).toString()
            } catch (_: Exception) {
                relative
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
