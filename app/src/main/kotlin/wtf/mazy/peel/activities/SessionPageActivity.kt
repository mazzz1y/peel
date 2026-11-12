package wtf.mazy.peel.activities

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.BaseSessionHost
import wtf.mazy.peel.browser.PeelContentDelegate
import wtf.mazy.peel.browser.PeelNavigationDelegate
import wtf.mazy.peel.browser.isBlankDocument
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.ui.browser.AppLockController

abstract class SessionPageActivity : BaseSessionHost() {

    override var baseUrl: String = ""
    override val webAppName: String
        get() = supportActionBar?.title?.toString().orEmpty().ifEmpty { lastLoadedUrl }

    override val showToolbar: Boolean = true
    override val isBrowserHost: Boolean = false
    protected open val isContentInitiatedWindow: Boolean = false

    private val ownerSettings: EffectiveSettings?
        get() = ownerWebAppUuid?.let(DataManager::webApp)?.let(DataManager::effectiveSettings)

    // A window of a web app shows its pages and uses its sandbox, so it shares that web app's lock.
    private val appLock by lazy {
        AppLockController(
            activity = this,
            ownerUuid = { ownerWebAppUuid },
            isProtected = {
                ownerWebAppUuid != null && (ownerSettings ?: effectiveSettings).biometricProtection
            },
            content = { browserContent },
            onSuccess = {},
            onFailure = { finish() },
        )
    }

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
        downloadHandler = createDownloadHandler()
        // The page's script connects once, as it loads. The web app's window has installed the
        // bridge by then, so starting undispatched attaches it before this session's page runs.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { attachPageBridge(session) }
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
        appLock.onStart()
        reattachSessionToView()
        pullToRefreshController.update(effectiveSettings)
        onSessionStarted()
    }

    override fun onResume() {
        super.onResume()
        appLock.applyRecentsProtection()
        appLock.showPromptIfNeeded {}
    }

    override fun onStop() {
        appLock.onStop()
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
        if (baseUrl.isEmpty() && !isBlankDocument(url)) baseUrl = url
    }

    protected open fun onPageTitleChanged(title: String?) = Unit

    override fun onPageStarted() = Unit
    override fun onFirstContentfulPaint() = Unit
}
