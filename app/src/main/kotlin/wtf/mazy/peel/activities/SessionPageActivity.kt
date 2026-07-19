package wtf.mazy.peel.activities

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.BaseSessionHost
import wtf.mazy.peel.browser.DownloadHandler
import wtf.mazy.peel.browser.PeelContentDelegate
import wtf.mazy.peel.browser.PeelNavigationDelegate
import wtf.mazy.peel.browser.PeelProgressDelegate
import wtf.mazy.peel.browser.PeelPromptDelegate
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.util.disableSystemBarContrastEnforcement

abstract class SessionPageActivity : BaseSessionHost() {

    override var baseUrl: String = ""
    override val webAppName: String
        get() = supportActionBar?.title?.toString().orEmpty().ifEmpty { lastLoadedUrl }

    override val externalLinkExcludeUuid: String? = null
    override val externalLinkPeelApps: List<WebApp>
        get() = DataManager.instance.activeWebsites
    override val externalLinkIncludeLoadHere: Boolean = false

    protected open val retainSessionAcrossRecreation = false
    protected open val showToolbar: Boolean = true

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
        onSessionHostReady()
    }

    protected abstract fun onSessionHostReady()

    protected fun connectSession(session: GeckoSession) {
        navigationDelegate = PeelNavigationDelegate(this)
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
    }

    protected fun displaySession(session: GeckoSession) {
        geckoSession = session
        geckoView?.setSession(session)
        geckoView?.coverUntilFirstPaint(themeBackgroundColor)
        setupPullToRefresh(effectiveSettings)
    }

    override fun onStart() {
        super.onStart()
        reattachSessionToView()
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
        geckoView?.releaseSession()
        geckoView = null
        if (!retainSessionAcrossRecreation || !isChangingConfigurations) {
            geckoSession?.close()
            geckoSession = null
        }
        super.onDestroy()
    }

    override fun onLocationChanged(url: String) {
        if (baseUrl.isEmpty() && url.isNotBlank() && url != "about:blank") baseUrl = url
        lastLoadedUrl = url
    }

    protected open fun onPageTitleChanged(title: String?) = Unit

    override fun onPageStarted() = Unit
    override fun onFirstContentfulPaint() = Unit

    override fun onWebFullscreenEnter() = Unit
    override fun onWebFullscreenExit() = Unit

    override fun findPeelAppMatches(url: String): List<WebApp> {
        return ExternalLinkMenu.findPeelAppMatches(
            DataManager.instance.activeWebsites,
            url,
            excludeUuid = null,
        )
    }
}
