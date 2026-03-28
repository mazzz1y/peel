package wtf.mazy.peel.browser

import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.HostIdentity

class PeelNavigationDelegate(private val host: SessionHost) : GeckoSession.NavigationDelegate {

    private var appLinkDialogShowing = false
    private var peelRoutingDialogShowing = false
    private var externalLinkDialogShowing = false

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
    ): GeckoResult<AllowOrDeny>? {
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

        if (settings.isOpenInPeelApp == true && !peelRoutingDialogShowing) {
            val matches = host.findPeelAppMatches(url)
            if (matches.isNotEmpty()) {
                peelRoutingDialogShowing = true
                host.runOnUi {
                    host.showPeelAppRoutingDialog(matches, url) {
                        peelRoutingDialogShowing = false
                    }
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }

        if (settings.isOpenUrlExternal == true) {
            if (HostIdentity.affinity(host.baseUrl, url) <= HostIdentity.TLD_ONLY) {
                showExternalIntentPrompt(
                    url, R.string.permission_prompt_open_externally,
                    { externalLinkDialogShowing }, { externalLinkDialogShowing = it },
                )
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
        val description = error.message ?: "Unknown error"
        val url = uri ?: host.baseUrl
        host.runOnUi { host.showConnectionError(description, url) }
        return null
    }

    fun resetDialogState() {
        appLinkDialogShowing = false
        peelRoutingDialogShowing = false
        externalLinkDialogShowing = false
    }

    private fun handleAppLink(url: String, settings: WebAppSettings) {
        when (settings.isAppLinksPermission) {
            WebAppSettings.PERMISSION_ON -> host.runOnUi { host.startExternalIntent(url.toUri()) }
            WebAppSettings.PERMISSION_ASK -> showExternalIntentPrompt(
                url, R.string.permission_prompt_open_app,
                { appLinkDialogShowing }, { appLinkDialogShowing = it },
            )
        }
    }

    private fun showExternalIntentPrompt(
        url: String,
        messageResId: Int,
        isDialogShowing: () -> Boolean,
        setDialogShowing: (Boolean) -> Unit,
    ) {
        if (isDialogShowing()) return
        setDialogShowing(true)
        host.runOnUi {
            host.showPermissionDialog(host.getString(messageResId, truncateUrl(url))) { result ->
                setDialogShowing(false)
                if (result == PermissionResult.ALLOW) {
                    host.startExternalIntent(url.toUri())
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
