package wtf.mazy.peel.browser

import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.withMonoSpan

class PeelNavigationDelegate(private val host: SessionHost) : GeckoSession.NavigationDelegate {

    private var appLinkDialogShowing = false
    private var externalMenuShowing = false
    var browsingExternally = false
    private var isInitialLoad = true

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        host.canGoBack = canGoBack
    }

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean
    ) {
        if (url != null) host.onLocationChanged(url)
    }

    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny> {
        val url = request.uri
        val settings = host.effectiveSettings

        if (url.startsWith("data:") && request.isDirectNavigation) {
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        if (!url.startsWith("http://") &&
            !url.startsWith("https://") &&
            !url.startsWith("file://") &&
            !url.startsWith("about:") &&
            !url.startsWith("blob:") &&
            !url.startsWith("data:")
        ) {
            handleAppLink(url, settings)
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        if (settings.isAlwaysHttps == true && url.startsWith("http://")) {
            host.runOnUi { host.loadURL(url.replaceFirst("http://", "https://")) }
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        if (url.startsWith("blob:") || url.startsWith("data:")) {
            return GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }

        if (settings.isOpenUrlExternal == true) {
            if (browsingExternally || isInitialLoad) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            val peelMatches = host.findPeelAppMatches(url)
            val isExternal = HostIdentity.affinity(host.baseUrl, url) <= HostIdentity.TLD_ONLY

            if (peelMatches.isNotEmpty() || isExternal) {
                if (!externalMenuShowing) {
                    externalMenuShowing = true
                    val redirect = request.isRedirect
                    host.runOnUi {
                        host.showExternalLinkMenu(url) { result ->
                            externalMenuShowing = false
                            when (result) {
                                ExternalLinkResult.LOAD_HERE -> {
                                    browsingExternally = true
                                    host.loadURL(url)
                                }

                                ExternalLinkResult.OPEN_IN_SYSTEM -> {
                                    host.startExternalIntent(url.toUri())
                                }

                                ExternalLinkResult.DISMISSED -> {
                                    if (redirect) {
                                        val fallback = host.lastLoadedUrl.ifEmpty { host.baseUrl }
                                        host.loadURL(fallback)
                                    }
                                }

                                is ExternalLinkResult.OpenInPeelApp -> {
                                    result.launcher()
                                }
                            }
                        }
                    }
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }

        if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
            host.runOnUi { host.loadURL(url) }
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError,
    ): GeckoResult<String>? {
        val description = ERROR_NAMES[error.code]
            ?: error.message
            ?: "ERROR_UNKNOWN (0x${error.code.toString(16)})"
        val url = uri ?: host.baseUrl
        host.runOnUi { host.showConnectionError(description, url) }
        return null
    }

    companion object {
        private val ERROR_NAMES: Map<Int, String> by lazy {
            WebRequestError::class.java.declaredFields
                .filter { it.name.startsWith("ERROR_") && it.type == Int::class.javaPrimitiveType }
                .associate { it.getInt(null) to it.name.removePrefix("ERROR_") }
        }
    }

    fun resetDialogState() {
        appLinkDialogShowing = false
        externalMenuShowing = false
        isInitialLoad = true
    }

    fun onInitialPageLoaded() {
        isInitialLoad = false
    }

    private fun handleAppLink(url: String, settings: WebAppSettings) {
        when (settings.isAppLinksPermission) {
            WebAppSettings.PERMISSION_OFF -> { /* silently block */
            }

            WebAppSettings.PERMISSION_ON -> host.runOnUi { host.startExternalIntent(url.toUri()) }
            WebAppSettings.PERMISSION_ASK -> {
                if (appLinkDialogShowing) return
                appLinkDialogShowing = true
                val truncated = truncateUrl(url)
                val message = host.getString(R.string.permission_prompt_open_app, truncated)
                    .withMonoSpan(truncated)
                host.runOnUi {
                    host.showPermissionDialog(message) { result ->
                        appLinkDialogShowing = false
                        if (result == PermissionResult.ALLOW) {
                            host.startExternalIntent(url.toUri())
                        }
                    }
                }
            }
        }
    }

    private fun truncateUrl(url: String, maxLen: Int = 80): String {
        if (url.length <= maxLen) return url
        val tail = 10
        return url.take(maxLen - tail - 1) + "…" + url.takeLast(tail)
    }
}
