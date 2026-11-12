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
import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.model.LinkChoice
import wtf.mazy.peel.model.LinkPromptOutcome
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.belongsToApp
import wtf.mazy.peel.util.toast
import wtf.mazy.peel.util.withBoldSpan
import wtf.mazy.peel.util.withMonoSpan

internal fun parseIntentUri(url: String): Intent? {
    if (!url.startsWith("intent:")) return null
    return runCatching {
        Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
            selector = null
            component = null
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }.getOrNull()
}

private class ExternalLinkPrompt(
    val url: String,
    val isRedirect: Boolean,
    val mustReload: Boolean,
    val isScriptNavigation: Boolean,
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

    // Gecko commits a denied redirect's redirector ("302 Moved") only after the decision lands.
    private var deniedRedirectOrigin: String? = null

    private var promptGeneration = 0

    @Volatile
    private var isInitialLoad = !isContentInitiatedWindow

    @Volatile
    var lastLocation: String = ""
        private set

    private val loadAttempt = LoadAttempt()

    val pageUrl: String?
        get() = lastLocation.takeUnless(::isBlankDocument) ?: loadAttempt.pendingUrl

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        host.canGoForward = canGoForward
    }

    // Also fires for subframe changes, which never move lastLocation.
    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        host.canGoBack = canGoBack
        val origin = deniedRedirectOrigin ?: return
        if (lastLocation == origin) return
        deniedRedirectOrigin = null
        if (canGoBack) host.runOnUi { host.goBackOrFinish() }
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
        deniedRedirectOrigin = null

        return when (val route = routeFor(url, settings, request)) {
            LinkRoute.Allow -> allow()
            LinkRoute.Blocked -> {
                showBlockedToast()
                refuse(request)
            }

            LinkRoute.Refused -> refuse(request)
            LinkRoute.AppLink -> {
                handleAppLink(url, settings, request.hasUserGesture)
                refuse(request)
            }

            is LinkRoute.Redirect -> redirectTo(route.target)
            // ALLOW on a TARGET_WINDOW_NEW request opens a new session via onNewSession instead
            // of loading in this one, so the current window must deny and reload explicitly.
            is LinkRoute.PromptExternal -> promptForExternalLink(
                route.target,
                isRedirect = request.isRedirect,
                mustReload = route.wasUpgraded || route.opensNewWindow,
                isScriptNavigation = request.triggerUri != null && !request.isRedirect &&
                        !request.hasUserGesture,
            )
        }
    }

    private fun routeFor(
        url: String,
        settings: EffectiveSettings,
        request: LoadRequest,
    ): LinkRoute = LinkRouter.route(
        url = url,
        settings = settings,
        nav = NavigationFacts(
            hasUserGesture = request.hasUserGesture,
            isRedirect = request.isRedirect,
            opensNewWindow = request.target == TARGET_WINDOW_NEW,
            isDirectNavigation = request.isDirectNavigation,
            hasTriggerPage = request.triggerUri != null,
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
            val context = host.hostContext
            context.toast(R.string.domain_blocked_toast)
        }
    }

    // No load will start, so nothing downstream will report the page as settled.
    private fun refuse(request: LoadRequest): GeckoResult<AllowOrDeny> {
        loadAttempt.settleActive()
        if (request.isRedirect) deniedRedirectOrigin = lastLocation
        host.runOnUi { host.onPageLoadEnded() }
        return deny()
    }

    override fun onNewSession(
        session: GeckoSession,
        uri: String,
    ): GeckoResult<GeckoSession> = host.openPopupSession(uri)

    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError,
    ): GeckoResult<String>? {
        if (uri == null || !LinkRouter.isBrowserScheme(uri)) return null
        if (isSpuriousError(error)) return null
        if (isCertError(error) && host.effectiveSettings.allowCertBypass) {
            loadAttempt.settleActive()
            return GeckoResult.fromValue(CertErrorPage.urlFor(uri))
        }

        val description = ERROR_NAMES[error.code] ?: error.message ?: return null
        loadAttempt.settleActive()
        host.runOnUi { host.showConnectionError(description, uri) }
        return null
    }

    fun onTopLevelLoadStart(url: String) {
        loadAttempt.start(url)
    }

    fun onTopLevelLoadStop() {
        val unanswered = loadAttempt.stop() ?: return
        if (hasCommittedContent()) return
        host.showConnectionError(host.hostResources.getString(R.string.empty_page), unanswered)
    }

    fun onSessionReset() {
        lastLocation = ""
        loadAttempt.reset()
    }

    fun resetDialogState() {
        appLinkDialogShowing = false
    }

    fun onDownloadStarted() {
        loadAttempt.downloadStarted()
        isOnJumpHost = true
    }

    private fun isInApp(url: String): Boolean =
        belongsToApp(host.policyOrigin, url, host.effectiveSettings)

    fun onPageLoadFinished() {
        if (!hasCommittedContent()) return
        isInitialLoad = false
    }

    private fun hasCommittedContent(): Boolean = !isBlankDocument(lastLocation)

    private fun promptForExternalLink(
        url: String,
        isRedirect: Boolean,
        mustReload: Boolean,
        isScriptNavigation: Boolean,
    ): GeckoResult<AllowOrDeny> {
        loadAttempt.settleActive()
        val decision = GeckoResult<AllowOrDeny>()
        val prompt = ExternalLinkPrompt(
            url = url,
            isRedirect = isRedirect,
            mustReload = mustReload,
            isScriptNavigation = isScriptNavigation,
            decision = decision,
        )
        pendingPrompts.addLast(prompt)
        if (pendingPrompts.size == 1) askAboutNextLink()
        return decision
    }

    private fun askAboutNextLink() {
        val prompt = pendingPrompts.firstOrNull() ?: return
        val generation = promptGeneration
        host.runOnUi {
            host.askAboutLink(prompt.url) { choice ->
                if (generation != promptGeneration) return@askAboutLink
                pendingPrompts.removeFirstOrNull()
                resolveExternalLink(prompt, choice) {
                    if (generation == promptGeneration) askAboutNextLink()
                }
            }
        }
    }

    /** Settles [prompt] by [choice]; [onDone] runs once nothing more is shown for it. */
    private fun resolveExternalLink(prompt: ExternalLinkPrompt, choice: LinkChoice, onDone: () -> Unit) {
        when (val outcome = LinkPromptOutcome.of(prompt.url, prompt.mustReload, choice)) {
            is LinkPromptOutcome.Proceed -> {
                markBrowsingExternally(prompt.url)
                prompt.settle(AllowOrDeny.ALLOW)
                onDone()
            }

            is LinkPromptOutcome.LoadHere -> {
                prompt.settle(AllowOrDeny.DENY)
                openHere(outcome.url)
                onDone()
            }

            is LinkPromptOutcome.Decline -> {
                prompt.settle(AllowOrDeny.DENY)
                if (outcome.leavesPeel) isOnJumpHost = true
                val stranded = outcome.abandonsWindow && isStranded(prompt)
                if (prompt.isRedirect && !stranded) deniedRedirectOrigin = lastLocation
                val finish = {
                    if (stranded) host.onInitialNavigationDenied()
                    onDone()
                }
                // Adding the site asks in a dialog over this window, which must outlive it.
                outcome.elsewhere?.let { host.openLinkElsewhere(it, onDone = finish) } ?: finish()
            }
        }
    }

    /** Loads [url] in this window as the user asked, so leaving the app does not ask again. */
    fun openHere(url: String) {
        markBrowsingExternally(url)
        host.loadURL(url)
    }

    private fun markBrowsingExternally(url: String) {
        if (!isInApp(url)) browsingExternally = true
    }

    private fun isStranded(prompt: ExternalLinkPrompt): Boolean =
        strandedWithoutContent() || strandedOnRedirector(prompt)

    fun cancelPendingPrompts() {
        promptGeneration++
        while (pendingPrompts.isNotEmpty()) {
            pendingPrompts.removeFirst().settle(AllowOrDeny.DENY)
        }
    }

    private fun strandedWithoutContent(): Boolean =
        isContentInitiatedWindow && !hasCommittedContent()

    // The window's only page sent the user elsewhere by script; declining leaves nothing to show.
    private fun strandedOnRedirector(prompt: ExternalLinkPrompt): Boolean =
        isContentInitiatedWindow && prompt.isScriptNavigation && !host.canGoBack

    private fun openInSystem(url: String) {
        host.startExternalIntent(url.toUri())
        isOnJumpHost = true
    }

    private fun handleAppLink(url: String, settings: EffectiveSettings, hasUserGesture: Boolean) {
        val intent = parseIntentUri(url)
        val targetPackage = intent?.`package`
        val browserFallback = intent?.getStringExtra("browser_fallback_url")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        val permission = settings.appLinksPermission.takeUnless {
            it == WebAppSettings.PERMISSION_ON && !hasUserGesture
        } ?: WebAppSettings.PERMISSION_ASK
        when (permission) {
            WebAppSettings.PERMISSION_OFF -> host.runOnUi {
                browserFallback?.let { loadFallback(it) }
            }

            WebAppSettings.PERMISSION_ON -> host.runOnUi { openInSystem(url) }

            WebAppSettings.PERMISSION_ASK -> showAppLinkDialog(
                url = url,
                targetPackage = targetPackage,
                display = intent?.data?.toString() ?: url,
                browserFallback = browserFallback,
            )
        }
    }

    private fun loadFallback(url: String) {
        val route = LinkRouter.route(
            url = url,
            settings = host.effectiveSettings,
            nav = NavigationFacts(
                hasUserGesture = false,
                isRedirect = true,
                opensNewWindow = false,
                isDirectNavigation = false,
                hasTriggerPage = false,
            ),
            context = linkContext(),
        )
        when (route) {
            LinkRoute.Blocked -> showBlockedToast()
            is LinkRoute.PromptExternal -> promptForExternalLink(
                route.target,
                isRedirect = false,
                mustReload = true,
                isScriptNavigation = false,
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
    ) {
        if (appLinkDialogShowing) return
        appLinkDialogShowing = true
        val message = buildAppLinkMessage(targetPackage, display)
        host.runOnUi {
            host.showPermissionDialog(message) { result, _, _ ->
                when (result) {
                    PermissionResult.ALLOW -> openInSystem(url)
                    PermissionResult.DENY -> browserFallback?.let { loadFallback(it) }
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
        loadAttempt.settleActive()
        host.runOnUi { host.loadURL(url) }
        return deny()
    }

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
