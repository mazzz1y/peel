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
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.belongsToApp
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

private class ExternalLinkPrompt(
    val url: String,
    val redirectFallback: String?,
    val reloadOnLoadHere: Boolean,
    private val decision: GeckoResult<AllowOrDeny>,
) {
    private var settled = false

    fun settle(value: AllowOrDeny) {
        if (settled) return
        settled = true
        decision.complete(value)
    }
}

class PeelNavigationDelegate(
    private val host: SessionHost,
    private val isContentInitiatedWindow: Boolean = false,
) : GeckoSession.NavigationDelegate {

    @Volatile
    var browsingExternally = false

    @Volatile
    var isOnJumpHost = false
        private set

    @Volatile
    private var appLinkDialogShowing = false

    private val pendingPrompts = ArrayDeque<ExternalLinkPrompt>()

    private var promptGeneration = 0

    @Volatile
    private var isInitialLoad = !isContentInitiatedWindow

    @Volatile
    var lastLocation: String = ""
        private set

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        host.canGoBack = canGoBack
    }

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean,
    ) {
        if (url.isNullOrBlank()) return
        if (url != lastLocation) isOnJumpHost = false
        lastLocation = url
        if (browsingExternally && isInApp(url)) browsingExternally = false
        host.onLocationChanged(url)
    }

    override fun onLoadRequest(
        session: GeckoSession,
        request: LoadRequest
    ): GeckoResult<AllowOrDeny> {
        val url = request.uri
        val settings = host.effectiveSettings

        return when (val route = routeFor(url, settings, request)) {
            LinkRoute.Allow -> allow()
            LinkRoute.Blocked -> {
                showBlockedToast()
                refuse()
            }

            LinkRoute.Refused -> refuse()
            LinkRoute.AppLink -> {
                handleAppLink(url, settings, request)
                refuse()
            }

            is LinkRoute.Redirect -> redirectTo(route.target)
            // ALLOW on a TARGET_WINDOW_NEW request opens a new session via onNewSession
            // instead of loading here, so "load here" must deny and reload explicitly.
            is LinkRoute.PromptExternal -> promptForExternalLink(
                route.target,
                redirectFallbackFor(request),
                reloadOnLoadHere = route.wasUpgraded || route.opensNewWindow,
            )
        }
    }

    private fun routeFor(
        url: String,
        settings: WebAppSettings,
        request: LoadRequest,
    ): LinkRoute = LinkRouter.route(
        url = url,
        settings = settings,
        nav = NavigationFacts(
            hasUserGesture = request.hasUserGesture,
            isRedirect = request.isRedirect,
            opensNewWindow = request.target == TARGET_WINDOW_NEW,
            isDirectNavigation = request.isDirectNavigation,
        ),
        context = linkContext(),
    )

    private fun linkContext(): LinkContext = LinkContext(
        policyOrigin = host.policyOrigin,
        browsingExternally = browsingExternally,
        isInitialLoad = isInitialLoad,
        hasPeelAppMatch = { host.findPeelAppMatches(it).isNotEmpty() },
    )

    private fun showBlockedToast() {
        host.runOnUi {
            val context = host.hostWindow.context
            NotificationUtils.showToastSafe(
                context,
                context.getString(R.string.domain_blocked_toast),
            )
        }
    }

    // No load will start, so nothing downstream will report the page as settled.
    private fun refuse(): GeckoResult<AllowOrDeny> {
        host.runOnUi { host.onPageLoadEnded() }
        return deny()
    }

    override fun onNewSession(
        session: GeckoSession,
        uri: String,
    ): GeckoResult<GeckoSession> = host.openPopupSession()

    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError,
    ): GeckoResult<String>? {
        if (uri == null || !LinkRouter.isBrowserScheme(uri)) return null
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
    }

    fun markCurrentPageAsJumpHost() {
        isOnJumpHost = true
    }

    private fun isInApp(url: String): Boolean =
        belongsToApp(host.policyOrigin, url, host.effectiveSettings)

    fun onPageLoadFinished() {
        if (!hasCommittedContent()) return
        isInitialLoad = false
    }

    private fun hasCommittedContent(): Boolean =
        lastLocation.isNotEmpty() && lastLocation != "about:blank"

    private fun promptForExternalLink(
        url: String,
        redirectFallback: String?,
        reloadOnLoadHere: Boolean,
    ): GeckoResult<AllowOrDeny> {
        val decision = GeckoResult<AllowOrDeny>()
        val prompt = ExternalLinkPrompt(
            url = url,
            redirectFallback = redirectFallback,
            reloadOnLoadHere = reloadOnLoadHere,
            decision = decision,
        )
        pendingPrompts.addLast(prompt)
        if (pendingPrompts.size == 1) showNextExternalLinkMenu()
        return decision
    }

    private fun showNextExternalLinkMenu() {
        val prompt = pendingPrompts.firstOrNull() ?: return
        val generation = promptGeneration
        host.runOnUi {
            host.showExternalLinkMenu(prompt.url) { result ->
                if (generation != promptGeneration) return@showExternalLinkMenu
                pendingPrompts.removeFirstOrNull()
                resolveExternalLink(prompt, result)
                showNextExternalLinkMenu()
            }
        }
    }

    private fun resolveExternalLink(prompt: ExternalLinkPrompt, result: ExternalLinkResult) {
        if (result == ExternalLinkResult.LoadHere) {
            browsingExternally = true
            if (prompt.reloadOnLoadHere) {
                prompt.settle(AllowOrDeny.DENY)
                host.loadURL(prompt.url)
            } else {
                prompt.settle(AllowOrDeny.ALLOW)
            }
            return
        }

        prompt.settle(AllowOrDeny.DENY)
        when (result) {
            ExternalLinkResult.OpenInSystem -> openInSystem(prompt.url, prompt.redirectFallback)
            ExternalLinkResult.OpenIncognito -> openIncognito(prompt.url, prompt.redirectFallback)
            ExternalLinkResult.Share -> shareUrl(prompt.url, prompt.redirectFallback)
            ExternalLinkResult.CopyLink -> copyLink(prompt.url, prompt.redirectFallback)
            ExternalLinkResult.Dismissed -> dismissRedirect(prompt.redirectFallback)
            is ExternalLinkResult.OpenInPeelApp -> result.launcher {}
            ExternalLinkResult.LoadHere -> Unit
        }
        if (abandonsWindow(result) && strandedWithoutContent()) {
            host.onInitialNavigationDenied()
        }
    }

    fun cancelPendingPrompts() {
        promptGeneration++
        while (pendingPrompts.isNotEmpty()) {
            pendingPrompts.removeFirst().settle(AllowOrDeny.DENY)
        }
    }

    private fun abandonsWindow(result: ExternalLinkResult): Boolean = when (result) {
        ExternalLinkResult.LoadHere,
        is ExternalLinkResult.OpenInPeelApp -> false

        ExternalLinkResult.OpenInSystem,
        ExternalLinkResult.OpenIncognito,
        ExternalLinkResult.Share,
        ExternalLinkResult.CopyLink,
        ExternalLinkResult.Dismissed -> true
    }

    private fun strandedWithoutContent(): Boolean =
        isContentInitiatedWindow && !hasCommittedContent()

    private fun openInSystem(url: String, redirectFallback: String?) {
        host.startExternalIntent(url.toUri())
        isOnJumpHost = true
        dismissRedirect(redirectFallback)
    }

    private fun openIncognito(url: String, redirectFallback: String?) {
        host.openIncognito(url)
        isOnJumpHost = true
        dismissRedirect(redirectFallback)
    }

    private fun shareUrl(url: String, redirectFallback: String?) {
        host.shareUrl(url)
        dismissRedirect(redirectFallback)
    }

    private fun copyLink(url: String, redirectFallback: String?) {
        host.copyLink(url)
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
        isOnJumpHost = true
        dismissRedirect(redirectFallback)
    }

    private fun applyAppLinkDeny(browserFallback: String?, redirectFallback: String?) {
        when {
            browserFallback != null -> loadFallback(browserFallback, redirectFallback)
            else -> dismissRedirect(redirectFallback)
        }
    }

    private fun loadFallback(url: String, redirectFallback: String?) {
        val route = LinkRouter.route(
            url = url,
            settings = host.effectiveSettings,
            nav = NavigationFacts(
                hasUserGesture = false,
                isRedirect = true,
                opensNewWindow = false,
                isDirectNavigation = false,
            ),
            context = linkContext(),
        )
        when (route) {
            LinkRoute.Blocked -> showBlockedToast()
            is LinkRoute.PromptExternal -> promptForExternalLink(
                route.target,
                redirectFallback,
                reloadOnLoadHere = true,
            )

            is LinkRoute.Redirect -> host.loadURL(route.target)
            else -> host.loadURL(url)
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
            host.showPermissionDialog(message) { result, _, _ ->
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

    private fun redirectTo(url: String): GeckoResult<AllowOrDeny> {
        host.runOnUi { host.loadURL(url) }
        return deny()
    }

    private fun redirectFallbackFor(request: LoadRequest): String? =
        if (request.isRedirect) {
            host.lastLoadedUrl.ifEmpty { host.baseUrl }.takeIf { it.isNotBlank() }
        } else null

    companion object {
        private val ERROR_NAMES: Map<Int, String> by lazy {
            WebRequestError::class.java.declaredFields
                .filter { it.name.startsWith("ERROR_") && it.type == Int::class.javaPrimitiveType }
                .associate { it.getInt(null) to it.name.removePrefix("ERROR_") }
        }

        private fun isSpuriousError(error: WebRequestError): Boolean =
            error.code == WebRequestError.ERROR_UNKNOWN &&
                    error.category == WebRequestError.ERROR_CATEGORY_UNKNOWN

        private fun isCertError(error: WebRequestError): Boolean =
            error.category == WebRequestError.ERROR_CATEGORY_SECURITY ||
                    error.code == WebRequestError.ERROR_SECURITY_BAD_CERT ||
                    error.code == WebRequestError.ERROR_SECURITY_SSL ||
                    error.code == WebRequestError.ERROR_BAD_HSTS_CERT

        private fun truncateUrl(url: String, maxLen: Int = 80, tail: Int = 10): String {
            if (url.length <= maxLen) return url
            return url.take(maxLen - tail - 1) + "…" + url.takeLast(tail)
        }

        private fun allow(): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        private fun deny(): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.DENY)
    }
}
