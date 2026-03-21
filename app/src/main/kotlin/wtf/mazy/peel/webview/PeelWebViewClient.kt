package wtf.mazy.peel.webview

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings

class PeelWebViewClient(private val host: WebViewClientHost) : WebViewClient() {

    private val autoAuthAttempted = mutableSetOf<String>()
    private var appLinkDialogShowing = false
    private var mainFrameError = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        autoAuthAttempted.clear()
        appLinkDialogShowing = false
        mainFrameError = false
        host.onPageStarted()
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        val settings = this.host.effectiveSettings
        if (settings.isUseBasicAuth == true) {
            val username = settings.basicAuthUsername.orEmpty()
            val password = settings.basicAuthPassword.orEmpty()
            val challengeKey = "$host:$realm"
            if (username.isNotEmpty() && autoAuthAttempted.add(challengeKey)) {
                handler.proceed(username, password)
                return
            }
        }
        this.host.showHttpAuthDialog(handler, host, realm)
    }

    override fun onPageFinished(view: WebView?, url: String) {
        host.navigationStartPoint.onPageFinished()
        if (mainFrameError) {
            super.onPageFinished(view, url)
            return
        }
        if (view != null) extractDynamicBarColor(view)
        host.onPageFullyLoaded()
        super.onPageFinished(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (view != null) host.navigationStartPoint.onVisitedHistoryUpdated(view, url, isReload)
    }

    fun extractDynamicBarColor(view: WebView) {
        if (host.effectiveSettings.isDynamicStatusBar != true) return
        if (host.isDarkSchemeActive && host.effectiveSettings.isAlgorithmicDarkening == true) {
            host.updateStatusBarColor(host.themeBackgroundColor)
            return
        }

        view.evaluateJavascript(EXTRACT_THEME_COLOR_JS) { result ->
            val color = parseWebColor(result.trim('"').trim()) ?: host.themeBackgroundColor
            host.updateStatusBarColor(color)
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame != true) return
        mainFrameError = true
        val description = error?.description?.toString() ?: "Unknown error"
        val url = request.url?.toString() ?: host.baseUrl
        host.showConnectionError(description, url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val settings = host.effectiveSettings
        if (settings.isAlwaysHttps == true && request.url.scheme == "http") {
            if (request.isForMainFrame) {
                view?.post {
                    host.showToast(host.getString(R.string.https_only_blocked))
                    host.finishActivity()
                }
            }
            return WebResourceResponse("text/plain", "utf-8", null)
        }

        if (settings.isBlockThirdPartyRequests == true) {
            if (!isHostMatch(request.url, host.baseUrl.toUri())) {
                return WebResourceResponse("text/plain", "utf-8", null)
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
        handler.cancel()

        val errorHost = error.url?.toUri()?.host?.removePrefix("www.")
        val baseHost = host.baseUrl.toUri().host?.removePrefix("www.")
        if (errorHost != null && errorHost != baseHost) return

        val reason =
            when (error.primaryError) {
                SslError.SSL_UNTRUSTED -> host.getString(R.string.ssl_error_unknown_authority)
                SslError.SSL_EXPIRED -> host.getString(R.string.ssl_error_expired)
                SslError.SSL_IDMISMATCH -> host.getString(R.string.ssl_error_id_mismatch)
                SslError.SSL_NOTYETVALID -> host.getString(R.string.ssl_error_notyetvalid)
                else -> host.getString(R.string.ssl_error_msg_line1)
            }
        host.showToast(host.getString(R.string.ssl_error_blocked, reason))
        host.finishActivity()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        host.runOnUi { host.applyColorScheme() }
        val url = request.url.toString()
        val settings = host.effectiveSettings

        if (url.startsWith("data:") && request.isForMainFrame) {
            return true
        }

        if (!url.startsWith("http://") &&
            !url.startsWith("https://") &&
            !url.startsWith("file://") &&
            !url.startsWith("about:") &&
            !url.startsWith("blob:") &&
            !url.startsWith("data:")
        ) {
            if (request.isForMainFrame) {
                handleAppLink(url, settings)
            }
            return true
        }

        if (settings.isAlwaysHttps == true && url.startsWith("http://")) {
            if (request.isRedirect) {
                host.showToast(host.getString(R.string.https_only_blocked))
                host.finishActivity()
                return true
            }
            host.loadURL(url.replaceFirst("http://", "https://"))
            return true
        }

        if (settings.isOpenUrlExternal == true) {
            if (!isHostMatch(url.toUri(), host.baseUrl.toUri())) {
                host.startExternalIntent(url.toUri())
                return true
            }
        }
        return false
    }

    private fun handleAppLink(url: String, settings: WebAppSettings) {
        val state = settings.isAppLinksPermission

        when (state) {
            WebAppSettings.PERMISSION_ON -> launchAppLink(url)
            WebAppSettings.PERMISSION_ASK -> {
                if (appLinkDialogShowing) return
                appLinkDialogShowing = true
                host.showPermissionDialog(buildAppLinkPrompt(url)) { result ->
                    appLinkDialogShowing = false
                    if (result == PermissionResult.ALLOW) launchAppLink(url)
                }
            }
        }
    }

    private fun buildAppLinkPrompt(url: String): CharSequence {
        val text = host.getString(R.string.permission_prompt_open_app, url)
        val urlStart = text.indexOf(url)
        if (urlStart < 0) return text
        return SpannableString(text).apply {
            setSpan(
                TypefaceSpan("monospace"),
                urlStart,
                urlStart + url.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun launchAppLink(url: String) {
        host.startExternalIntent(url.toUri())
    }

    companion object {
        fun isHostMatch(requestUri: Uri, baseUri: Uri): Boolean {
            val requestHost = requestUri.host?.removePrefix("www.") ?: return false
            val baseHost = baseUri.host?.removePrefix("www.") ?: return false
            return requestHost == baseHost || requestHost.endsWith(".$baseHost")
        }

        // rgb(r, g, b), rgba(r, g, b, a), rgb(r g b), rgb(r g b / a)
        private val rgbRegex = Regex("""rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)""")

        fun parseWebColor(raw: String): Int? {
            if (raw.isBlank()) return null
            val normalized = normalizeHexColor(raw)
            try {
                val color = normalized.toColorInt()
                if (Color.alpha(color) == 0) return null
                return color
            } catch (_: IllegalArgumentException) {
            }
            rgbRegex.find(raw)?.let { match ->
                val r = match.groupValues[1].toIntOrNull() ?: return null
                val g = match.groupValues[2].toIntOrNull() ?: return null
                val b = match.groupValues[3].toIntOrNull() ?: return null
                return Color.rgb(r, g, b)
            }
            return null
        }

        private fun normalizeHexColor(color: String): String {
            if (!color.startsWith('#')) return color
            val hex = color.substring(1)
            return when (hex.length) {
                3 -> "#${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                4 -> "#${hex[3]}${hex[3]}${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                8 -> "#${hex.substring(6, 8)}${hex.take(6)}"
                else -> color
            }
        }

        private val EXTRACT_THEME_COLOR_JS =
            """
            (function() {
                var isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
                var metas = document.querySelectorAll('meta[name="theme-color"]');
                for (var i = 0; i < metas.length; i++) {
                    var media = metas[i].getAttribute('media');
                    if (!media) continue;
                    if (isDark && media.indexOf('dark') !== -1) return metas[i].content;
                    if (!isDark && media.indexOf('light') !== -1) return metas[i].content;
                }

                function isOpaque(c) {
                    return c && c !== 'rgba(0, 0, 0, 0)' && c !== 'transparent';
                }
                var bodyBg = document.body ? getComputedStyle(document.body).backgroundColor : '';
                if (isOpaque(bodyBg)) return bodyBg;
                var htmlBg = getComputedStyle(document.documentElement).backgroundColor;
                if (isOpaque(htmlBg)) return htmlBg;

                return '';
            })()
        """
                .trimIndent()
    }
}
