package wtf.mazy.peel.activities

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.BaseSessionHost
import wtf.mazy.peel.browser.DownloadHandler
import wtf.mazy.peel.browser.PeelContentDelegate
import wtf.mazy.peel.browser.PeelNavigationDelegate
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp

abstract class SessionPageActivity : BaseSessionHost() {

    override var baseUrl: String = ""
    override val webAppName: String
        get() = supportActionBar?.title?.toString().orEmpty().ifEmpty { lastLoadedUrl }

    override val externalLinkExcludeUuid: String? = null
    override val externalLinkPeelApps: List<WebApp>
        get() = DataManager.instance.activeWebsites
    override val externalLinkIncludeLoadHere: Boolean = false

    override val showToolbar: Boolean = true
    override val isBrowserHost: Boolean = false
    protected open val isContentInitiatedWindow: Boolean = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (exitFullscreenIfActive()) return
            goBackOrFinish()
        }
    }

    override var canGoBack: Boolean
        get() = super.canGoBack
        set(value) {
            super.canGoBack = value
            updateBackCallbackEnabled()
        }

    override fun updateBackCallbackEnabled() {
        backCallback.isEnabled = canGoBack || isWebFullscreen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_Browser)
        super.onCreate(savedInstanceState)
        applyWindowFlags(effectiveSettings)

        toolbar?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            it.setNavigationOnClickListener { finish() }
        }

        geckoView?.coverUntilFirstPaint(themeBackgroundColor)
        onBackPressedDispatcher.addCallback(this, backCallback)
        onSessionHostReady()
    }

    protected abstract fun onSessionHostReady()

    protected fun connectSession(session: GeckoSession) {
        navigationDelegate = PeelNavigationDelegate(this, isContentInitiatedWindow)
        downloadHandler = DownloadHandler(
            activity = this,
            getRuntime = { GeckoRuntimeProvider.getRuntime(this) },
            scope = lifecycleScope,
            webappName = webAppName,
        )
        bindDelegates(
            session,
            PeelContentDelegate(
                host = this,
                onDownload = { response -> downloadHandler.onExternalResponse(response) },
                onTitleChange = { title -> onPageTitleChanged(title) },
            ),
        )
    }

    override fun onStart() {
        super.onStart()
        reattachSessionToView()
        pullToRefreshController.update(effectiveSettings)
        onSessionStarted()
    }

    override fun onStop() {
        onSessionStopped()
        super.onStop()
        geckoView?.releaseSession()
    }

    protected open fun onSessionStarted() = Unit

    protected open fun onSessionStopped() = Unit

    override fun onDestroy() {
        closeGeckoSession()
        geckoView = null
        super.onDestroy()
    }

    override fun onLocationChanged(url: String) {
        if (baseUrl.isEmpty() && url.isNotBlank() && url != "about:blank") baseUrl = url
    }

    protected open fun onPageTitleChanged(title: String?) = Unit

    override fun onPageStarted() = Unit
    override fun onFirstContentfulPaint() = Unit
}
