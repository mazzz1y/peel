package wtf.mazy.peel.activities

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.BaseSessionHost
import wtf.mazy.peel.browser.DownloadHandler
import wtf.mazy.peel.browser.PeelContentDelegate
import wtf.mazy.peel.browser.PeelNavigationDelegate
import wtf.mazy.peel.browser.PeelPermissionDelegate
import wtf.mazy.peel.browser.PeelProgressDelegate
import wtf.mazy.peel.browser.PeelPromptDelegate
import wtf.mazy.peel.browser.SessionContextRegistry
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.util.disableSystemBarContrastEnforcement

abstract class SessionPageActivity : BaseSessionHost() {

    override var baseUrl: String = ""
    override val webAppName: String
        get() = supportActionBar?.title?.toString().orEmpty().ifEmpty { lastLoadedUrl }

    override val externalLinkExcludeUuid: String? = null
    override val externalLinkPeelApps: List<WebApp>
        get() = DataManager.instance.activeWebsites
    override val externalLinkIncludeLoadHere: Boolean = false

    protected open val showToolbar: Boolean = true
    protected open val isContentInitiatedWindow: Boolean = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            geckoSession?.goBack()
        }
    }

    override var canGoBack: Boolean
        get() = super.canGoBack
        set(value) {
            super.canGoBack = value
            backCallback.isEnabled = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_Browser)
        enableEdgeToEdge()
        disableSystemBarContrastEnforcement()
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(themeBackgroundColor.toDrawable())
        setupSessionHostLayout(showToolbar = showToolbar)
        applyWindowFlags(effectiveSettings)

        toolbar?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            it.setNavigationOnClickListener { finish() }
        }

        geckoView?.coverUntilFirstPaint(themeBackgroundColor)
        SessionContextRegistry.register(this, sessionContextId)
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
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = PeelContentDelegate(
            host = this,
            onDownload = { response -> downloadHandler.onExternalResponse(response) },
            onTitleChange = { title -> onPageTitleChanged(title) },
        )
        session.progressDelegate = PeelProgressDelegate(this)
        session.promptDelegate = PeelPromptDelegate(this)
        session.permissionDelegate = PeelPermissionDelegate(this)
        attachScrollDelegate(session)
    }

    protected fun displaySession(session: GeckoSession) {
        geckoSession = session
        geckoView?.setSession(session)
        geckoView?.coverUntilFirstPaint(themeBackgroundColor)
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
        SessionContextRegistry.unregister(this)
    }

    override fun onLocationChanged(url: String) {
        if (baseUrl.isEmpty() && url.isNotBlank() && url != "about:blank") baseUrl = url
    }

    protected open fun onPageTitleChanged(title: String?) = Unit

    override fun onPageStarted() = Unit
    override fun onFirstContentfulPaint() = Unit

    override fun onWebFullscreenEnter() {
        setBrowserControlsFullscreen(true)
        pullToRefreshController.setSuspended(true)
    }

    override fun onWebFullscreenExit() {
        setBrowserControlsFullscreen(false)
        pullToRefreshController.setSuspended(false)
    }
}
