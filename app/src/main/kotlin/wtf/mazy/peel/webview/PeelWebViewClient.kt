package wtf.mazy.peel.webview

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
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
import wtf.mazy.peel.model.DataManager

class PeelWebViewClient(private val host: WebViewClientHost) : WebViewClient() {

    private var barColorGeneration = 0
    private var dynamicBarColorRetryCount = 0
    private var dynamicBarColorRetryScheduled = false
    private var dynamicBarColorRetryRunnable: Runnable? = null
    private var dynamicBarColorRetryView: WebView? = null

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        resetDynamicBarColorRetry(clearCount = true)
        barColorGeneration += 1
        super.onPageStarted(view, url, favicon)
        host.onPageStarted()
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        this.host.showHttpAuthDialog(handler, host, realm)
    }

    override fun onPageFinished(view: WebView?, url: String) {
        host.showNotification()
        if (view != null) extractDynamicBarColor(view)
        super.onPageFinished(view, url)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (view != null) extractDynamicBarColor(view)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (view != null) extractDynamicBarColor(view)
    }

    fun extractDynamicBarColor(view: WebView) {
        if (host.effectiveSettings.isDynamicStatusBar != true) return
        if (host.isForceDarkActive) {
            resetDynamicBarColorRetry(clearCount = true)
            host.updateStatusBarColor(host.themeBackgroundColor)
            return
        }

        val generation = barColorGeneration
        view.evaluateJavascript(EXTRACT_PAGE_COLOR_JS) { result ->
            if (generation != barColorGeneration) return@evaluateJavascript
            val probe = parsePageColorProbe(result) ?: run {
                scheduleDynamicBarColorRetry(view, generation)
                return@evaluateJavascript
            }

            if (probe.surfaceColor != null) {
                resetDynamicBarColorRetry(clearCount = true)
                host.updateStatusBarColor(probe.surfaceColor)
                return@evaluateJavascript
            }

            if (probe.themeColor != null) {
                host.updateStatusBarColor(probe.themeColor)
                if (probe.isComplete) {
                    resetDynamicBarColorRetry(clearCount = true)
                    return@evaluateJavascript
                }
            }

            scheduleDynamicBarColorRetry(view, generation)
        }
    }

    private fun scheduleDynamicBarColorRetry(view: WebView, generation: Int) {
        if (dynamicBarColorRetryScheduled) return
        if (dynamicBarColorRetryCount >= DYNAMIC_BAR_COLOR_MAX_RETRIES) return
        resetDynamicBarColorRetry(clearCount = false)
        dynamicBarColorRetryScheduled = true
        dynamicBarColorRetryCount += 1
        dynamicBarColorRetryView = view
        val runnable = Runnable {
            if (generation != barColorGeneration) return@Runnable
            dynamicBarColorRetryScheduled = false
            extractDynamicBarColor(view)
        }
        dynamicBarColorRetryRunnable = runnable
        view.postDelayed(runnable, DYNAMIC_BAR_COLOR_RETRY_DELAY_MS)
    }

    fun clearDynamicBarColorRetry() {
        resetDynamicBarColorRetry(clearCount = true)
    }

    private fun resetDynamicBarColorRetry(clearCount: Boolean) {
        dynamicBarColorRetryRunnable?.let { runnable ->
            dynamicBarColorRetryView?.removeCallbacks(runnable)
        }
        dynamicBarColorRetryRunnable = null
        dynamicBarColorRetryView = null
        dynamicBarColorRetryScheduled = false
        if (clearCount) {
            dynamicBarColorRetryCount = 0
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            host.showToast(host.getString(R.string.site_not_found))
            host.finishActivity()
        }
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

        val errorHost = error.url?.toUri()?.host
        val baseHost = host.baseUrl.toUri().host
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
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        host.runOnUi { host.setDarkModeIfNeeded() }
        var url = request.url.toString()
        val webapp = host.webappUuid?.let { DataManager.instance.getWebApp(it) }

        if (!url.startsWith("http://") &&
            !url.startsWith("https://") &&
            !url.startsWith("file://") &&
            !url.startsWith("about:")
        ) {
            if (request.isForMainFrame && request.hasGesture()) {
                try {
                    host.startExternalIntent(url.toUri())
                } catch (_: Exception) {
                }
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
        private const val DYNAMIC_BAR_COLOR_MAX_RETRIES = 120
        private const val DYNAMIC_BAR_COLOR_RETRY_DELAY_MS = 250L

        private data class PageColorProbe(
            val surfaceColor: Int?,
            val themeColor: Int?,
            val isComplete: Boolean,
        )

        fun isHostMatch(requestUri: Uri, baseUri: Uri): Boolean {
            val requestHost = requestUri.host?.removePrefix("www.") ?: return true
            val baseHost = baseUri.host?.removePrefix("www.") ?: return true
            return requestHost == baseHost || requestHost.endsWith(".$baseHost")
        }

        private val rgbRegex = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""")

        fun parseWebColor(raw: String): Int? {
            if (raw.isBlank()) return null
            try {
                return raw.toColorInt()
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

        private fun parsePageColorProbe(result: String): PageColorProbe? {
            val raw = result.trim('"').trim()
            val parts = raw.split("||", limit = 3)
            if (parts.size != 3) return null
            return PageColorProbe(
                surfaceColor = parseWebColor(parts[0].trim()),
                themeColor = parseWebColor(parts[1].trim()),
                isComplete = parts[2].trim() == "1",
            )
        }

        private val EXTRACT_PAGE_COLOR_JS =
            """
            (function() {
                function isOpaque(c) {
                    return c && c !== 'rgba(0, 0, 0, 0)' && c !== 'transparent';
                }
                var ready = document.readyState === 'complete';
                function pack(surface, theme) {
                    return (surface || '') + '||' + (theme || '') + '||' + (ready ? '1' : '0');
                }
                var bodyBg = document.body ? getComputedStyle(document.body).backgroundColor : '';
                if (isOpaque(bodyBg)) return pack(bodyBg, '');
                var htmlBg = getComputedStyle(document.documentElement).backgroundColor;
                if (isOpaque(htmlBg)) return pack(htmlBg, '');
                var isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
                var metas = document.querySelectorAll('meta[name="theme-color"]');
                var fallback = null;
                for (var i = 0; i < metas.length; i++) {
                    var media = metas[i].getAttribute('media');
                    if (!media) { fallback = metas[i].content; continue; }
                    if (isDark && media.indexOf('dark') !== -1) return pack('', metas[i].content);
                    if (!isDark && media.indexOf('light') !== -1) return pack('', metas[i].content);
                }
                return pack('', fallback || '');
            })()
        """
                .trimIndent()
    }
}
