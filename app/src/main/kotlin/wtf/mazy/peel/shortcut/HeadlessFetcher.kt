package wtf.mazy.peel.shortcut

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.WebAppSettings
import java.net.HttpURLConnection
import java.net.URL

data class FetchCandidate(
    val title: String?,
    val icon: Bitmap?,
    val source: String,
)

data class FetchResult(
    val candidates: List<FetchCandidate>,
    val title: String?,
    val startUrl: String?,
    val redirectedUrl: String?,
) {
    companion object {
        val EMPTY = FetchResult(emptyList(), null, null, null)
    }
}

private data class PageSnapshot(val url: String, val title: String?, val jsData: JSONObject)

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
    private val userAgent: String = GeckoSession.getDefaultUserAgent()
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
                FetchResult.EMPTY
            } catch (_: Exception) {
                FetchResult.EMPTY
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
        var capturedUrl: String? = null
        var currentTitle: String? = null
        val collected = mutableListOf<PageSnapshot>()
        val messages = Channel<Unit>(Channel.CONFLATED)

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
                        if (message is JSONObject) {
                            collected += PageSnapshot(
                                capturedUrl ?: loadUrl, currentTitle, message,
                            )
                            messages.trySend(Unit)
                            postProgress(appContext.getString(
                                R.string.fetch_step_processing, hostOf(capturedUrl ?: loadUrl),
                            ))
                        }
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
                if (url != null && !url.startsWith("about:")) {
                    capturedUrl = url
                    postProgress(appContext.getString(R.string.fetch_step_loading_host, hostOf(url)))
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrEmpty()) currentTitle = title
            }
        }

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

        messages.receive()
        while (withTimeoutOrNull(COLLECT_MS) { messages.receive() } != null) { /* drain */ }

        val pageUrl = capturedUrl ?: loadUrl

        postProgress(appContext.getString(R.string.fetch_step_downloading))

        val redirected = if (pageUrl.trimEnd('/') != url.trimEnd('/')) pageUrl else null

        return withContext(Dispatchers.IO) {
            resolve(collected, currentTitle, pageUrl, redirected)
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
        collected: List<PageSnapshot>,
        lastTitle: String?,
        pageUrl: String,
        redirected: String?,
    ): FetchResult {
        val icons = mutableListOf<FetchCandidate>()
        for (snap in collected.reversed()) {
            scope.ensureActive()
            val title = snap.title ?: hostOf(snap.url)
            decodeIcons(snap.jsData, title)?.let { icons += it }
        }

        val manifest = collected.lastOrNull()?.jsData?.optJSONObject("manifest")
            ?: run {
                scope.ensureActive()
                probeManifestFallback(pageUrl)
            }
        val manifestName = manifest?.optString("name")?.ifEmpty { null }
            ?: manifest?.optString("short_name")?.ifEmpty { null }
        val startUrl = manifest?.optString("start_url")?.ifEmpty { null }
            ?.let { resolveUrl(collected.lastOrNull()?.url ?: pageUrl, it) }

        val bestTitle = manifestName ?: collected.lastOrNull()?.title ?: lastTitle

        val candidates = if (icons.isNotEmpty()) {
            icons.deduplicatedBySize()
        } else {
            listOfNotNull(
                bestTitle?.let { FetchCandidate(it, null, "PWA") }
            )
        }

        return FetchResult(candidates, bestTitle, startUrl, redirected)
    }

    private fun decodeIcons(jsData: JSONObject, title: String): List<FetchCandidate>? {
        val iconData = jsData.optJSONArray("iconData") ?: return null
        val result = mutableListOf<FetchCandidate>()
        for (i in 0 until iconData.length()) {
            scope.ensureActive()
            val obj = iconData.optJSONObject(i) ?: continue
            val dataUrl = obj.optString("dataUrl").takeIf { it.isNotEmpty() } ?: continue
            val source = obj.optString("source", "HTML")
            val bmp = decodeDataUrl(dataUrl) ?: continue
            result += FetchCandidate(title, bmp, source)
        }
        return result.ifEmpty { null }
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap? {
        val base64 = dataUrl.substringAfter(",", "")
        if (base64.isEmpty() || base64.length > MAX_ICON_BYTES * 4 / 3) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.size > MAX_ICON_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > MAX_ICON_DIM || bounds.outHeight > MAX_ICON_DIM) return null
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun probeManifestFallback(pageUrl: String): JSONObject? {
        val tried = mutableSetOf<String>()
        for (base in linkedSetOf(pageUrl, url)) {
            for (path in MANIFEST_FALLBACK_PATHS) {
                scope.ensureActive()
                for (candidate in listOf(resolveUrl(base, path), "${originOf(base)}$path")) {
                    if (tried.add(candidate)) {
                        tryFetchManifest(candidate)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun tryFetchManifest(manifestUrl: String): JSONObject? {
        return try {
            val conn = openConnection(manifestUrl)
            try {
                if (conn.responseCode != 200) return null
                if (conn.contentType?.lowercase().orEmpty().contains("html")) return null
                if (conn.contentLength > MAX_ICON_BYTES) return null
                val sb = StringBuilder()
                val buf = CharArray(8192)
                conn.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (sb.length + n > MAX_ICON_BYTES) return null
                        sb.appendRange(buf, 0, n)
                    }
                }
                if (sb.isEmpty()) return null
                val json = JSONObject(sb.toString())
                if (json.has("name") || json.has("short_name") || json.has("icons")) json else null
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

    private fun finish(result: FetchResult = FetchResult.EMPTY) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        onResult(result)
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val COLLECT_MS = 2_000L
        private const val CONNECTION_TIMEOUT_MS = 4_000
        private const val MAX_ICON_BYTES = 1024 * 1024
        private const val MAX_ICON_DIM = 1024
        private val MANIFEST_FALLBACK_PATHS =
            listOf("/manifest.json", "/manifest.webmanifest", "/site.webmanifest")

        private fun hostOf(url: String): String =
            try { URL(url).host } catch (_: Exception) { url }

        private fun resolveUrl(base: String, relative: String): String =
            try {
                URL(URL(base), relative).toString()
            } catch (_: Exception) {
                relative
            }

        private fun originOf(url: String): String =
            try {
                URL(url).let {
                    if (it.port != -1 && it.port != it.defaultPort)
                        "${it.protocol}://${it.host}:${it.port}"
                    else "${it.protocol}://${it.host}"
                }
            } catch (_: Exception) { url }

        private val SOURCE_PRIORITY = mapOf(
            "PWA" to 0, "Apple" to 1, "HTML" to 2, "Favicon" to 3,
        )

        private fun List<FetchCandidate>.deduplicatedBySize(): List<FetchCandidate> {
            val seen = mutableSetOf<Pair<Int, Int>>()
            return sortedWith(
                compareBy<FetchCandidate> { SOURCE_PRIORITY[it.source] ?: 99 }
                    .thenByDescending { it.icon!!.width * it.icon.height }
            ).filter { seen.add(it.icon!!.width to it.icon.height) }
        }
    }
}
