package wtf.mazy.peel.browser

import android.content.Intent
import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
import org.mozilla.geckoview.WebRequestError
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.normalizedHost
import wtf.mazy.peel.util.withBoldSpan
import wtf.mazy.peel.util.withMonoSpan

internal fun parseIntentUri(url: String): Intent? {
    if (!url.startsWith("intent:")) return null
    return runCatching {
        Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
            selector = null
            component = null
        }
    }.getOrNull()
}

class PeelNavigationDelegate(private val host: SessionHost) : GeckoSession.NavigationDelegate {

    var browsingExternally = false

    private var appLinkDialogShowing = false
    private var externalMenuShowing = false
    private var isInitialLoad = true
    private var lastLocation: String = ""

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        host.canGoBack = canGoBack
    }

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean,
    ) {
        if (url != null) {
            lastLocation = url
            host.onLocationChanged(url)
        }
    }

    override fun onLoadRequest(
        session: GeckoSession,
        request: LoadRequest
    ): GeckoResult<AllowOrDeny> {
        val url = request.uri
        val settings = host.effectiveSettings

        return when {
            url.startsWith("data:") && request.isDirectNavigation -> deny()
            !isBrowserScheme(url) -> {
                handleAppLink(url, settings, request)
                deny()
            }

            settings.isAlwaysHttps == true && url.startsWith("http://") -> redirectToHttps(url)
            isPassthroughScheme(url) -> allow()
            settings.isOpenUrlExternal == true -> handleExternalRouting(url, request)
            request.target == TARGET_WINDOW_NEW -> redirectToCurrentTab(url)
            else -> allow()
        }
    }

    override fun onNewSession(
        session: GeckoSession,
        uri: String,
    ): GeckoResult<GeckoSession> {
        host.runOnUi { host.loadURL(uri) }
        return GeckoResult.fromValue(null)
    }

    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError,
    ): GeckoResult<String>? {
        if (uri == null || !isBrowserScheme(uri)) return null
        if (isSpuriousError(error)) return null
        if (isCertError(error) && host.effectiveSettings.isAllowCertBypass == true) {
            return GeckoResult.fromValue(CertErrorPage.urlFor(uri))
        }

        val description = ERROR_NAMES[error.code] ?: error.message ?: return null
        host.runOnUi { host.showConnectionError(description, uri) }
        return null
    }

    fun resetDialogState() {
        appLinkDialogShowing = false
        externalMenuShowing = false
    }

    fun onPageLoadFinished() {
        if (lastLocation.isEmpty() || lastLocation == "about:blank") return
        isInitialLoad = false
    }

    private fun handleExternalRouting(url: String, request: LoadRequest): GeckoResult<AllowOrDeny> {
        if (browsingExternally || isInitialLoad) {
            return if (request.target == TARGET_WINDOW_NEW) deny() else allow()
        }

        if (isSameOrigin(host.baseUrl, url)) return allow()

        val isExternal = HostIdentity.affinity(host.baseUrl, url) <= HostIdentity.TLD_ONLY
        if (isExternal && isExplicitDownload(url)) return allow()

        val peelMatches = host.findPeelAppMatches(url)
        if (peelMatches.isEmpty() && !isExternal) {
            return if (request.target == TARGET_WINDOW_NEW) redirectToCurrentTab(url) else allow()
        }

        showExternalLinkMenu(url, redirectFallbackFor(request))
        return deny()
    }

    private fun showExternalLinkMenu(url: String, redirectFallback: String?) {
        if (externalMenuShowing) return
        externalMenuShowing = true
        host.runOnUi {
            host.showExternalLinkMenu(url) { result ->
                externalMenuShowing = false
                when (result) {
                    ExternalLinkResult.LoadHere -> loadExternallyInCurrentTab(url)
                    ExternalLinkResult.OpenInSystem -> openInSystem(url, redirectFallback)
                    ExternalLinkResult.Dismissed -> dismissRedirect(redirectFallback)
                    is ExternalLinkResult.OpenInPeelApp -> result.launcher()
                }
            }
        }
    }

    private fun loadExternallyInCurrentTab(url: String) {
        browsingExternally = true
        host.loadURL(url)
    }

    private fun openInSystem(url: String, redirectFallback: String?) {
        host.startExternalIntent(url.toUri())
        dismissRedirect(redirectFallback)
    }

    private fun dismissRedirect(redirectFallback: String?) {
        redirectFallback?.let { host.dismissRedirectToFallback(it) }
    }

    private fun handleAppLink(url: String, settings: WebAppSettings, request: LoadRequest) {
        val intent = parseIntentUri(url)
        val targetPackage = intent?.`package`
        val browserFallback = intent?.getStringExtra("browser_fallback_url")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val redirectFallback = redirectFallbackFor(request)

        when (settings.isAppLinksPermission) {
            WebAppSettings.PERMISSION_OFF -> host.runOnUi {
                applyAppLinkDeny(browserFallback, redirectFallback)
            }

            WebAppSettings.PERMISSION_ON -> host.runOnUi {
                applyAppLinkAllow(url, redirectFallback)
            }

            WebAppSettings.PERMISSION_ASK -> showAppLinkDialog(
                url = url,
                targetPackage = targetPackage,
                display = intent?.data?.toString() ?: url,
                browserFallback = browserFallback,
                redirectFallback = redirectFallback,
            )
        }
    }

    private fun applyAppLinkAllow(url: String, redirectFallback: String?) {
        host.startExternalIntent(url.toUri())
        dismissRedirect(redirectFallback)
    }

    private fun applyAppLinkDeny(browserFallback: String?, redirectFallback: String?) {
        when {
            browserFallback != null -> host.loadURL(browserFallback)
            else -> dismissRedirect(redirectFallback)
        }
    }

    private fun showAppLinkDialog(
        url: String,
        targetPackage: String?,
        display: String,
        browserFallback: String?,
        redirectFallback: String?,
    ) {
        if (appLinkDialogShowing) return
        appLinkDialogShowing = true
        val message = buildAppLinkMessage(targetPackage, display)
        host.runOnUi {
            host.showPermissionDialog(message) { result ->
                when (result) {
                    PermissionResult.ALLOW -> applyAppLinkAllow(url, redirectFallback)
                    PermissionResult.DENY -> applyAppLinkDeny(browserFallback, redirectFallback)
                }
                appLinkDialogShowing = false
            }
        }
    }

    private fun buildAppLinkMessage(targetPackage: String?, display: String): CharSequence {
        if (targetPackage != null) {
            return host.hostResources.getString(
                R.string.permission_prompt_open_app_intent,
                targetPackage
            )
                .withMonoSpan(targetPackage)
                .withBoldSpan(targetPackage)
        }
        val truncated = truncateUrl(display)
        return host.hostResources.getString(R.string.permission_prompt_open_app, truncated)
            .withMonoSpan(truncated)
            .withBoldSpan(truncated)
    }

    private fun redirectToHttps(url: String): GeckoResult<AllowOrDeny> {
        host.runOnUi { host.loadURL(url.replaceFirst("http://", "https://")) }
        return deny()
    }

    private fun redirectToCurrentTab(url: String): GeckoResult<AllowOrDeny> {
        host.runOnUi { host.loadURL(url) }
        return deny()
    }

    private fun redirectFallbackFor(request: LoadRequest): String? =
        if (request.isRedirect) host.lastLoadedUrl.ifEmpty { host.baseUrl } else null

    companion object {
        private val BROWSER_SCHEMES = arrayOf(
            "http://", "https://", "moz-extension://",
            "file://", "about:", "blob:", "data:",
        )

        private val PASSTHROUGH_SCHEMES = arrayOf("blob:", "data:", "moz-extension://")

        private val ERROR_NAMES: Map<Int, String> by lazy {
            WebRequestError::class.java.declaredFields
                .filter { it.name.startsWith("ERROR_") && it.type == Int::class.javaPrimitiveType }
                .associate { it.getInt(null) to it.name.removePrefix("ERROR_") }
        }

        private fun isBrowserScheme(url: String): Boolean =
            BROWSER_SCHEMES.any { url.startsWith(it) }

        private fun isPassthroughScheme(url: String): Boolean =
            PASSTHROUGH_SCHEMES.any { url.startsWith(it) }

        private fun isSpuriousError(error: WebRequestError): Boolean =
            error.code == WebRequestError.ERROR_UNKNOWN &&
                    error.category == WebRequestError.ERROR_CATEGORY_UNKNOWN

        private fun isCertError(error: WebRequestError): Boolean =
            error.category == WebRequestError.ERROR_CATEGORY_SECURITY ||
                    error.code == WebRequestError.ERROR_SECURITY_BAD_CERT ||
                    error.code == WebRequestError.ERROR_SECURITY_SSL ||
                    error.code == WebRequestError.ERROR_BAD_HSTS_CERT

        private fun isSameOrigin(base: String, url: String): Boolean {
            val baseHost = base.normalizedHost() ?: return false
            val targetHost = url.normalizedHost() ?: return false
            return baseHost == targetHost && schemeOf(base) == schemeOf(url)
        }

        private fun schemeOf(url: String): String? {
            val end = url.indexOf("://").takeIf { it > 0 } ?: return null
            return url.substring(0, end)
        }

        private fun isExplicitDownload(url: String): Boolean {
            val query = url.substringAfter('?', "").lowercase()
            if (query.isEmpty()) return false
            return "response-content-disposition=attachment" in query ||
                    "rscd=attachment" in query
        }

        private fun truncateUrl(url: String, maxLen: Int = 80, tail: Int = 10): String {
            if (url.length <= maxLen) return url
            return url.take(maxLen - tail - 1) + "…" + url.takeLast(tail)
        }

        private fun allow(): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        private fun deny(): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.DENY)
    }
}
