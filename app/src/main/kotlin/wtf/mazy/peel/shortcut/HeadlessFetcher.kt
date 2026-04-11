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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.WebAppSettings
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

private data class IconRef(val href: String, val sizes: String, val source: String)

private data class PageInfo(
    val title: String?,
    val manifestUrl: String,
    val iconRefs: List<IconRef>,
)

private data class PendingMessage(
    val requestId: Int,
    val deferred: CompletableDeferred<JSONObject>,
)

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
    private var desktopMode = false
    private var finished = false
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var port: WebExtension.Port? = null

    private var pending: PendingMessage? = null
    private var portReady: CompletableDeferred<Unit>? = null
    private var requestCounter = 0

    fun start() {
        desktopMode = settings.isRequestDesktop == true

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
                    override fun onConnect(port: WebExtension.Port) {
                        this@HeadlessFetcher.port = port
                        portReady?.complete(Unit)
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                if (message !is JSONObject) return
                                val current = pending ?: return
                                val requestId = message.optInt("requestId", -1)
                                if (requestId != current.requestId) return
                                if (!isValidResponse(message)) return
                                current.deferred.complete(message)
                            }

                            override fun onDisconnect(port: WebExtension.Port) {
                                if (this@HeadlessFetcher.port === port) {
                                    this@HeadlessFetcher.port = null
                                    pending?.deferred?.complete(JSONObject().put("mode", "error"))
                                }
                            }
                        })
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
                    postProgress(appContext.getString(R.string.fetch_step_loading_host))
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                pending?.deferred?.complete(JSONObject().put("mode", "error"))
                return null
            }
        }

        session.open(GeckoRuntimeProvider.getRuntime(appContext))
        attachView(session)
        session.setActive(true)

        val rootUrl = originOf(loadUrl) + "/"
        val hasSubPath = try {
            val path = URL(loadUrl).path
            path.isNotEmpty() && path != "/"
        } catch (_: Exception) {
            false
        }

        val pages = mutableListOf<PageInfo>()

        if (hasSubPath) {
            val root = loadPage(session, rootUrl)
            if (root != null) pages += root
        }

        val target = loadPage(session, loadUrl) ?: return FetchResult.EMPTY
        pages += target

        val pageUrl = capturedUrl ?: loadUrl
        val redirected = if (pageUrl.trimEnd('/') != url.trimEnd('/')) pageUrl else null

        postProgress(appContext.getString(R.string.fetch_step_downloading_manifest))

        val manifestUrls = pages.mapNotNull { it.manifestUrl.ifEmpty { null } }.distinct()

        var manifest: JSONObject? = null
        var manifestUrlUsed: String? = null
        for (mUrl in manifestUrls) {
            if (!isAllowedRemoteUrl(mUrl)) continue
            val text = sendCommand(JSONObject().put("cmd", "fetch-manifest").put("url", mUrl))
                ?.optString("text", "") ?: ""
            manifest = parseManifest(text)
            if (manifest != null) {
                manifestUrlUsed = mUrl
                break
            }
        }

        if (manifest == null) {
            val fallbacks = listOf("/manifest.webmanifest", "/manifest.json", "/site.webmanifest")
            for (path in fallbacks) {
                val fallbackUrl = originOf(pageUrl) + path
                if (!isAllowedRemoteUrl(fallbackUrl)) continue
                val text =
                    sendCommand(JSONObject().put("cmd", "fetch-manifest").put("url", fallbackUrl))
                        ?.optString("text", "") ?: ""
                manifest = parseManifest(text)
                if (manifest != null) {
                    manifestUrlUsed = fallbackUrl
                    break
                }
            }
        }

        val manifestName = manifest?.optString("name")?.ifEmpty { null }
            ?: manifest?.optString("short_name")?.ifEmpty { null }
        val startUrl = manifest?.optString("start_url")?.ifEmpty { null }
            ?.let { resolveUrl(manifestUrlUsed ?: pageUrl, it) }
        val bestTitle = manifestName ?: pages.lastOrNull()?.title

        val allRefs = mutableListOf<IconRef>()
        val seenHrefs = mutableSetOf<String>()

        if (manifest != null) {
            val manifestBase = manifestUrlUsed ?: pageUrl
            val icons = manifest.optJSONArray("icons")
            if (icons != null) {
                for (i in 0 until icons.length()) {
                    val obj = icons.optJSONObject(i) ?: continue
                    val src = obj.optString("src").takeIf { it.isNotEmpty() } ?: continue
                    if (src.endsWith(".svg")) continue
                    val resolved = resolveUrl(manifestBase, src)
                    if (!isAllowedRemoteUrl(resolved)) continue
                    if (seenHrefs.add(resolved)) {
                        allRefs += IconRef(resolved, obj.optString("sizes", ""), "PWA")
                    }
                }
            }
        }

        for (page in pages.reversed()) {
            for (ref in page.iconRefs) {
                if (ref.href.endsWith(".svg")) continue
                if (!isAllowedRemoteUrl(ref.href)) continue
                if (seenHrefs.add(ref.href)) allRefs += ref
            }
        }

        val faviconUrl = originOf(pageUrl) + "/favicon.ico"
        if (isAllowedRemoteUrl(faviconUrl) && seenHrefs.add(faviconUrl)) {
            allRefs += IconRef(faviconUrl, "", "Favicon")
        }

        postProgress(appContext.getString(R.string.fetch_step_downloading_icons))
        val iconUrls = allRefs.take(MAX_ICONS).map { it.href }
        val iconResults = if (iconUrls.isNotEmpty()) {
            val urlArray = JSONArray(iconUrls)
            val resp = sendCommand(JSONObject().put("cmd", "fetch-icons").put("urls", urlArray))
            resp?.optJSONArray("results")
        } else null

        val pageTitle = pages.lastOrNull()?.title ?: hostOf(pageUrl)
        val icons = mutableListOf<FetchCandidate>()
        val refByHref = allRefs.associateBy { it.href }

        if (iconResults != null) {
            for (i in 0 until minOf(iconResults.length(), MAX_ICONS)) {
                val item = iconResults.optJSONObject(i) ?: continue
                val href = item.optString("url")
                val dataUrl = item.optString("dataUrl", "")
                if (dataUrl.isEmpty()) continue
                val bmp = decodeDataUrl(dataUrl) ?: continue
                val ref = refByHref[href]
                val source = ref?.source ?: "PWA"
                val title = if (source == "PWA") manifestName ?: pageTitle else pageTitle
                icons += FetchCandidate(title, bmp, source)
            }
        }

        val candidates = if (icons.isNotEmpty()) {
            icons.deduplicatedBySize()
        } else {
            listOfNotNull(bestTitle?.let { FetchCandidate(it, null, "PWA") })
        }
        return FetchResult(candidates, bestTitle, startUrl, redirected)
    }

    private suspend fun loadPage(session: GeckoSession, url: String): PageInfo? {
        if (!isAllowedRemoteUrl(url)) return null
        portReady = CompletableDeferred()
        loadUri(session, url)
        portReady?.await()
        portReady = null
        val msg = sendCommand(JSONObject().put("cmd", "scrape-page")) ?: return null
        if (msg.optString("mode") == "error") return null
        return parsePageInfo(msg)
    }

    private suspend fun sendCommand(command: JSONObject): JSONObject? {
        val p = port ?: return null
        val requestId = nextRequestId()
        val deferred = CompletableDeferred<JSONObject>()
        pending = PendingMessage(requestId, deferred)
        command.put("requestId", requestId)
        p.postMessage(command)
        val msg = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { deferred.await() }
        pending = null
        return msg
    }

    private fun loadUri(session: GeckoSession, url: String) {
        session.loadUri(url)
    }

    private fun parsePageInfo(msg: JSONObject): PageInfo {
        val title = msg.optString("pageTitle", "").ifBlank { null }
        val rawManifestUrl = msg.optString("manifestUrl", "")
        val manifestUrl = if (isAllowedRemoteUrl(rawManifestUrl)) rawManifestUrl else ""
        val iconRefs = mutableListOf<IconRef>()
        val arr = msg.optJSONArray("iconUrls")
        if (arr != null) {
            val limit = minOf(arr.length(), MAX_ICONS)
            for (i in 0 until limit) {
                val obj = arr.optJSONObject(i) ?: continue
                val href = obj.optString("href").takeIf { it.isNotEmpty() } ?: continue
                if (!isAllowedRemoteUrl(href)) continue
                iconRefs += IconRef(
                    href = href,
                    sizes = obj.optString("sizes", ""),
                    source = obj.optString("source", "HTML"),
                )
            }
        }
        return PageInfo(title, manifestUrl, iconRefs)
    }

    private fun parseManifest(text: String): JSONObject? {
        if (text.isEmpty() || text.length > MAX_MANIFEST_BYTES) return null
        return try {
            val json = JSONObject(text)
            if (json.has("name") || json.has("short_name") || json.has("icons")) json else null
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap? {
        if (dataUrl.length > MAX_DATA_URL_CHARS) return null
        val base64 = dataUrl.substringAfter(",", "")
        if (base64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.size > MAX_ICON_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > MAX_ICON_DIM || bounds.outHeight > MAX_ICON_DIM) return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        } catch (_: Exception) {
            null
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

    private fun teardown() {
        val p = port
        val session = geckoSession
        val view = geckoView
        port = null
        geckoSession = null
        geckoView = null
        pending?.deferred?.cancel()
        pending = null
        portReady?.cancel()
        portReady = null
        if (session != null || view != null) {
            handler.post {
                try {
                    p?.disconnect()
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
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val MAX_ICON_BYTES = 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_ICON_DIM = 1024
        private const val MAX_ICONS = 20
        private const val MAX_DATA_URL_CHARS = 2 * 1024 * 1024
        private const val MAX_URL_LENGTH = 2048

        private fun hostOf(url: String): String =
            try {
                val u = URL(url)
                val path = u.path.orEmpty().trimEnd('/')
                if (path.isEmpty()) u.host else "${u.host}$path"
            } catch (_: Exception) {
                url
            }

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
            } catch (_: Exception) {
                url
            }

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

        private fun isValidResponse(msg: JSONObject): Boolean {
            return when (msg.optString("mode")) {
                "page" -> msg.has("manifestUrl") && msg.has("iconUrls")
                "manifest" -> msg.has("text")
                "icons" -> msg.has("results")
                "error" -> true
                else -> false
            }
        }
    }

    private fun nextRequestId(): Int = ++requestCounter

    private fun isAllowedRemoteUrl(url: String): Boolean {
        if (url.isBlank() || url.length > MAX_URL_LENGTH) return false
        return try {
            val u = URL(url)
            u.protocol == "https" || u.protocol == "http"
        } catch (_: Exception) {
            false
        }
    }
}
