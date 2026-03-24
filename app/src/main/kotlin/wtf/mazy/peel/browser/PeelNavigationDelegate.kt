package wtf.mazy.peel.browser

import android.net.Uri
import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings

class PeelNavigationDelegate(private val host: SessionHost) : GeckoSession.NavigationDelegate {

    private var appLinkDialogShowing = false

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        host.canGoBack = canGoBack
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {}

    override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
        host.canGoBack = true
        if (url != null) host.currentUrl = url
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

        if (settings.isOpenUrlExternal == true) {
            if (!isHostMatch(url.toUri(), host.baseUrl.toUri())) {
                host.runOnUi { host.startExternalIntent(url.toUri()) }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
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

    fun clearAutoAuth() {
        appLinkDialogShowing = false
    }

    private fun handleAppLink(url: String, settings: WebAppSettings) {
        val state = settings.isAppLinksPermission
        when (state) {
            WebAppSettings.PERMISSION_ON -> host.runOnUi { host.startExternalIntent(url.toUri()) }
            WebAppSettings.PERMISSION_ASK -> {
                if (appLinkDialogShowing) return
                appLinkDialogShowing = true
                host.runOnUi {
                    host.showPermissionDialog(
                        host.getString(R.string.permission_prompt_open_app, url)
                    ) { result ->
                        appLinkDialogShowing = false
                        if (result == PermissionResult.ALLOW) {
                            host.startExternalIntent(url.toUri())
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun isHostMatch(requestUri: Uri, baseUri: Uri): Boolean {
            val requestHost = requestUri.host?.removePrefix("www.") ?: return false
            val baseHost = baseUri.host?.removePrefix("www.") ?: return false
            return requestHost == baseHost || requestHost.endsWith(".$baseHost")
        }
    }
}
