package wtf.mazy.peel.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.LocaleUtils.fileEnding

class PeelWebViewClient(
    private val host: WebViewClientHost,
) : WebViewClient() {

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        this.host.showHttpAuthDialog(handler, host, realm)
    }

    override fun onPageFinished(view: WebView?, url: String) {
        if (url == "about:blank") {
            view?.loadUrl("file:///android_asset/errorSite/error_${fileEnding}.html")
        }
        view?.evaluateJavascript(
            "document.addEventListener(\"visibilitychange\"," +
                "function(event){event.stopImmediatePropagation();},true);",
            null,
        )
        host.showNotification()
        super.onPageFinished(view, url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (host.urlOnFirstPageload.isEmpty()) {
            host.urlOnFirstPageload = request.url.toString()
        }

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
        if (host.effectiveSettings.isIgnoreSslErrors == true) {
            handler.proceed()
            return
        }
        handler.cancel()

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

    override fun onLoadResource(view: WebView, url: String?) {
        super.onLoadResource(view, url)
        if (host.effectiveSettings.isRequestDesktop == true) {
            view.evaluateJavascript(
                """
                var needsForcedWidth = document.documentElement.clientWidth < 1200;
                if(needsForcedWidth) {
                  document.querySelector('meta[name="viewport"]').setAttribute('content',
                    'width=1200px, initial-scale=' + (document.documentElement.clientWidth / 1200));
                }
                """
                    .trimIndent(),
                null,
            )
        }
        view.evaluateJavascript(
            "document.addEventListener(\"visibilitychange\"," +
                "(event)=>{event.stopImmediatePropagation();});",
            null,
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        host.runOnUi { host.setDarkModeIfNeeded() }
        var url = request.url.toString()
        val webapp = host.webappUuid?.let { DataManager.instance.getWebApp(it) }

        if (!url.startsWith("http://") &&
            !url.startsWith("https://") &&
            !url.startsWith("file://") &&
            !url.startsWith("about:")) {
            if (request.isForMainFrame && request.hasGesture()) {
                try {
                    host.startExternalIntent(url.toUri())
                } catch (_: Exception) {}
            }
            return true
        }

        if (webapp?.effectiveSettings?.isAlwaysHttps == true && url.startsWith("http://")) {
            if (request.isRedirect) {
                host.showToast(host.getString(R.string.https_only_blocked))
                host.finishActivity()
                return true
            }
            url = url.replaceFirst("http://", "https://")
        }

        if (webapp?.effectiveSettings?.isOpenUrlExternal == true) {
            if (!isHostMatch(url.toUri(), webapp.baseUrl.toUri())) {
                host.startExternalIntent(url.toUri())
                return true
            }
        }
        host.loadURL(url)
        return true
    }

    companion object {
        fun isHostMatch(requestUri: Uri, baseUri: Uri): Boolean {
            val requestHost = requestUri.host ?: return true
            val baseHost = baseUri.host ?: return true
            return requestHost == baseHost || requestHost.endsWith(".$baseHost")
        }
    }
}
