package wtf.mazy.peel.activities

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.BaseSessionHost
import wtf.mazy.peel.browser.BrowserContextMenu
import wtf.mazy.peel.browser.DownloadHandler
import wtf.mazy.peel.browser.DownloadService
import wtf.mazy.peel.browser.PeelContentDelegate
import wtf.mazy.peel.browser.PeelNavigationDelegate
import wtf.mazy.peel.browser.PeelPermissionDelegate
import wtf.mazy.peel.browser.PeelProgressDelegate
import wtf.mazy.peel.browser.PeelPromptDelegate
import wtf.mazy.peel.browser.StartupAuthReturnTracker
import wtf.mazy.peel.gecko.ExtensionStateEvent
import wtf.mazy.peel.gecko.ExtensionStateListener
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.gecko.NestedGeckoView
import wtf.mazy.peel.media.MediaPlaybackManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.FloatingControlsView
import wtf.mazy.peel.ui.browser.AutoReloadController
import wtf.mazy.peel.ui.browser.BiometricUnlockController
import wtf.mazy.peel.ui.browser.LaunchOverlayController
import wtf.mazy.peel.ui.browser.SystemBarController
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.ui.extensions.ExtensionPickerDialog
import wtf.mazy.peel.ui.extensions.SessionExtensionActions
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.normalizedHost

class BrowserActivity : BaseSessionHost() {
    var webappUuid: String? = null

    private val sessionExtensionActions by lazy {
        SessionExtensionActions(
            activity = this,
            onExtensionsReady = { _ ->
                rebuildFloatingControls()
            },
            onNavigateToUrl = ::loadURL,
        )
    }

    private val extensionStateListener = ExtensionStateListener { event ->
        val session = geckoSession ?: return@ExtensionStateListener
        sessionExtensionActions.attach(session)
        SessionExtensionActions.extensionsChanged = false
        if (event == ExtensionStateEvent.ADDED || event == ExtensionStateEvent.REMOVED) {
            session.reload()
        }
    }

    private var historyPurged = false
    override var canGoBack: Boolean = false
        set(value) {
            if (historyPurged && value) return
            field = value
        }
    var currentUrl = ""

    init {
        currentlyReloading = true
    }

    private val launchedFromMenu by lazy {
        intent.getBooleanExtra(Const.INTENT_LAUNCHED_FROM_MENU, false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val webapp: WebApp
        get() = DataManager.instance.getWebApp(webappUuid!!)!!

    private var floatingControls: FloatingControlsView? = null
    private lateinit var permissionDelegate: PeelPermissionDelegate
    private lateinit var promptDelegate: PeelPromptDelegate
    private var contextMenu: BrowserContextMenu? = null

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uriString = intent.getStringExtra(DownloadService.EXTRA_URI) ?: return
            val mimeType = intent.getStringExtra(DownloadService.EXTRA_MIME_TYPE)
            showDownloadSnackbar(uriString.toUri(), mimeType)
        }
    }

    private var pageLoadHandled = false
    private var mediaPlaybackManager: MediaPlaybackManager? = null
    private var sessionSetupJob: Job? = null
    private lateinit var startupAuthReturnTracker: StartupAuthReturnTracker
    private var isStartupAuthTrackingActive = true

    private var cachedSettings: WebAppSettings? = null
    private var isStartupComplete = false

    @Volatile
    private var cachedPeelApps: List<WebApp> = emptyList()

    private val systemBarController by lazy {
        SystemBarController(window, ::themeBackgroundColor)
    }

    private val launchOverlayController = LaunchOverlayController(
        mainHandler = mainHandler,
        isDestroyed = { isDestroyed },
        animationDurationMs = UI_ANIMATION_DURATION_MS,
        fallbackDelayMs = OVERLAY_HIDE_FALLBACK_MS,
    )

    private val biometricController by lazy {
        BiometricUnlockController(
            activity = this,
            getWebappUuid = { webappUuid },
            onSuccess = {
                launchOverlayController.hideFallback()
                launchSessionExtensionsAndLoad(
                    effectiveSettings,
                    sharedUrlFromIntent() ?: webapp.baseUrl
                )
            },
            onFailure = { finish() },
        )
    }

    private val autoReloadController = AutoReloadController(
        mainHandler = mainHandler,
        onReload = {
            currentlyReloading = true
            reloadCurrentPage()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        liveInstances.add(this)

        window.setBackgroundDrawable(themeBackgroundColor.toDrawable())
        setupSessionHostLayout(showToolbar = false)
        launchOverlay?.let { overlay ->
            overlay.setBackgroundColor(themeBackgroundColor)
            overlay.alpha = 1f
            overlay.visibility = View.VISIBLE
        }

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        ensureDataReady(webappUuid, forceReload = false) {
            continueStartupAfterDataReady()
        }
    }

    private fun continueStartupAfterDataReady() {
        if (isFinishing || isDestroyed) return
        if (webappUuid == null || DataManager.instance.getWebApp(webappUuid!!) == null) {
            NotificationUtils.showToast(this, getString(R.string.webapp_not_found))
            finishAndRemoveTask()
            return
        }

        cachedSettings = DataManager.instance.resolveEffectiveSettings(webapp)
        applyTaskSnapshotProtection()
        setupGeckoView()
        biometricController.registerReceiver()

        val needsBiometric = effectiveSettings.isBiometricProtection == true
        biometricController.showPromptIfNeeded(needsBiometric) {
            launchOverlayController.arm { systemBarController.suppressNextAnimation = true }
        }
        if (!needsBiometric) {
            launchSessionExtensionsAndLoad(
                effectiveSettings,
                sharedUrlFromIntent() ?: webapp.baseUrl
            )
        }

        setupBackNavigation()
        isStartupComplete = true
    }

    override fun onStart() {
        super.onStart()
        GeckoRuntimeProvider.addExtensionStateListener(extensionStateListener)
        geckoSession?.let { session ->
            session.setActive(true)
            GeckoRuntimeProvider.getRuntime(this)
                .webExtensionController.setTabActive(session, true)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, downloadCompleteReceiver,
            IntentFilter(DownloadService.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val uuid = webappUuid ?: return
        SessionExtensionActions.setActive(sessionExtensionActions)
        if (SessionExtensionActions.extensionsChanged) {
            SessionExtensionActions.extensionsChanged = false
            geckoSession?.let { sessionExtensionActions.attach(it) }
        }
        ensureDataReady(uuid, forceReload = true) {
            applyResumedState()
        }
    }

    private fun applyResumedState() {
        if (!isStartupComplete) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val uuid = webappUuid ?: return
        if (DataManager.instance.getWebApp(uuid) == null) return
        cachedSettings = DataManager.instance.resolveEffectiveSettings(webapp)
        applyVisualSettings(effectiveSettings)

        if (effectiveSettings.isShowNotification == true && floatingControls == null) {
            floatingControls = createFloatingControls(uuid)
        }

        biometricController.showPromptIfNeeded(
            effectiveSettings.isBiometricProtection == true,
        ) { launchOverlayController.arm { systemBarController.suppressNextAnimation = true } }

        if (launchOverlayController.isVisible && !biometricController.isPromptActive && pageLoadHandled) {
            launchOverlayController.hideFallback()
        }

        autoReloadController.start(effectiveSettings)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (_: Exception) {
        }
        if (!isStartupComplete) return
        floatingControls?.remove()
        floatingControls = null
        autoReloadController.stop()
    }

    private fun createFloatingControls(uuid: String): FloatingControlsView {
        return FloatingControlsView(
            parent = findViewById(R.id.browserContent),
            webappUuid = uuid,
            onHome = { loadURL(webapp.baseUrl) },
            onReload = ::reloadCurrentPage,
            onShare = { shareCurrentUrl() },
            onExtensions = if (SessionExtensionActions.hasExtensions)
                ({ ExtensionPickerDialog.show(this, sessionExtensionActions) }) else null,
        )
    }

    private fun shareCurrentUrl() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                },
                null,
            ),
        )
    }

    private fun rebuildFloatingControls() {
        val uuid = webappUuid ?: return
        val current = floatingControls ?: return
        current.remove()
        floatingControls = createFloatingControls(uuid)
    }

    override fun onStop() {
        super.onStop()
        GeckoRuntimeProvider.removeExtensionStateListener(extensionStateListener)
        if (!isStartupComplete) {
            biometricController.onStop(); return
        }
        val settings = effectiveSettings

        if (settings.isAllowMediaPlaybackInBackground != true) {
            geckoSession?.setActive(false)
        }

        geckoSession?.let { session ->
            GeckoRuntimeProvider.getRuntime(this)
                .webExtensionController.setTabActive(session, false)
        }
        sessionExtensionActions.dismissPopup()

        biometricController.onStop()
    }

    override fun onDestroy() {
        liveInstances.remove(this)
        if (isFinishing && effectiveSettings.isClearCache == true && liveInstances.isEmpty()) {
            val runtime = GeckoRuntimeProvider.getRuntime(this)
            runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
        }
        launchOverlayController.release()
        biometricController.unregisterReceiver()
        systemBarController.release()
        mediaPlaybackManager?.release()
        mediaPlaybackManager = null
        sessionExtensionActions.detach()
        geckoView?.releaseSession()
        geckoSession?.close()
        geckoSession = null
        geckoView = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColorScheme()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID) ?: return

        if (newUuid == webappUuid) {
            sharedUrlFromIntent()?.let { loadURL(it) }
            return
        }

        ensureDataReady(newUuid, forceReload = true) {
            applyLoadedWebAppIntent(newUuid)
        }
    }

    private fun ensureDataReady(uuid: String?, forceReload: Boolean, action: () -> Unit) {
        if (uuid == null) {
            action(); return
        }
        lifecycleScope.launch {
            DataManager.instance.ensureWebAppLoaded(uuid, forceReload = forceReload)
            if (!isFinishing && !isDestroyed) action()
            if (!isFinishing && !isDestroyed) {
                cachedPeelApps = DataManager.instance.queryAllWebApps()
            }
        }
    }

    private fun applyLoadedWebAppIntent(newUuid: String) {
        if (isFinishing || isDestroyed) return
        if (DataManager.instance.getWebApp(newUuid) == null) return
        webappUuid = newUuid
        cachedSettings = DataManager.instance.resolveEffectiveSettings(webapp)
        biometricController.resetForSwap()
        systemBarController.resetForSwap()
        mediaPlaybackManager?.release()
        mediaPlaybackManager = null

        val settings = effectiveSettings
        configureSession(settings)
        applyVisualSettings(settings)
        applyTaskSnapshotProtection()

        launchSessionExtensionsAndLoad(settings, sharedUrlFromIntent() ?: webapp.baseUrl)
    }

    override val webAppName: String
        get() = webapp.title

    override val effectiveSettings: WebAppSettings
        get() = cachedSettings ?: DataManager.instance.resolveEffectiveSettings(webapp)

    override val baseUrl: String
        get() = webapp.baseUrl

    override val sessionContextId: String?
        get() = webapp.resolveContextId()
    override val sessionPrivateMode: Boolean
        get() = webapp.resolvePrivateMode()

    override val externalLinkExcludeUuid: String?
        get() = webappUuid
    override val externalLinkPeelApps: List<WebApp>
        get() = cachedPeelApps
    override val externalLinkIncludeLoadHere: Boolean = true

    private fun showToast(message: String) {
        NotificationUtils.showToast(this, message)
    }

    private fun showDownloadSnackbar(uri: Uri, mimeType: String?) {
        val root = findViewById<View>(android.R.id.content)
        Snackbar.make(root, getString(R.string.download_complete), Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.open)) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType ?: "application/octet-stream")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    showToast(getString(R.string.no_app_found))
                }
            }
            .show()
    }

    override fun showConnectionError(description: String, url: String) {
        if (launchOverlayController.isVisible) {
            showToast(getString(R.string.connection_error, description))
            finish()
            return
        }
        super.showConnectionError(description, url)
    }

    override fun updateStatusBarColor(color: Int) {
        if (biometricController.isPromptActive) return
        systemBarController.update(
            color,
            launchOverlayController.isVisible,
            UI_ANIMATION_DURATION_MS
        )
    }

    override fun onLocationChanged(url: String) {
        historyPurged = false
        currentUrl = url
        handleStartupAuthHistoryReset(url)
        if (url.normalizedHost() == webapp.baseUrl.normalizedHost()) {
            navigationDelegate.browsingExternally = false
        }
    }

    override fun onPageStarted() {
        pageLoadHandled = false
        permissionDelegate.clearPagePermissions()
        promptDelegate.clearAutoAuth()
        navigationDelegate.resetDialogState()
    }

    override fun onPageFullyLoaded() {
        super.onPageFullyLoaded()
        closeStartupAuthTrackingIfInitialBaseLoaded()
        lastLoadedUrl = currentUrl
        if (pageLoadHandled || biometricController.isPromptActive) return
        pageLoadHandled = true
        if (launchOverlayController.isVisible) {
            launchOverlayController.hideFallback()
        }
    }

    override fun onFirstContentfulPaint() {
        if (launchOverlayController.isVisible && !biometricController.isPromptActive) {
            launchOverlayController.hideNow()
        }
    }

    override fun hideSystemBars() {
        systemBarController.hide()
    }

    override fun showSystemBars() {
        systemBarController.show(effectiveSettings.isShowFullscreen == true)
    }

    private fun setupGeckoView() {
        val settings = effectiveSettings
        bindViews()
        systemBarController.attach(
            rootView = findViewById(R.id.browser_root),
            applyDynamicColor = settings.isDynamicStatusBar == true,
        )

        downloadHandler = DownloadHandler(
            activity = this,
            getRuntime = { GeckoRuntimeProvider.getRuntime(this) },
            scope = lifecycleScope,
            webappName = webapp.title,
        )
        navigationDelegate = PeelNavigationDelegate(this)
        permissionDelegate = PeelPermissionDelegate(this)
        promptDelegate = PeelPromptDelegate(this)

        configureSession(settings)
        applyVisualSettings(settings)
    }

    private fun configureSession(settings: WebAppSettings) {
        sessionSetupJob?.cancel()
        (geckoSession?.contentDelegate as? PeelContentDelegate)?.exitFullscreen()
        geckoView?.releaseSession()
        geckoSession?.close()

        currentUrl = ""
        lastLoadedUrl = ""
        canGoBack = false
        currentlyReloading = true
        pageLoadHandled = false
        filePathCallback = null
        navigationDelegate.resetDialogState()
        navigationDelegate.browsingExternally = false
        permissionDelegate.clearPagePermissions()
        promptDelegate.clearAutoAuth()
        (geckoView as? NestedGeckoView)?.resetScrollPosition()
        autoReloadController.stop()
        startupAuthReturnTracker = StartupAuthReturnTracker(webapp.baseUrl)
        isStartupAuthTrackingActive = true
        historyPurged = false

        val session = createSession(settings)
        geckoSession = session

        session.navigationDelegate = navigationDelegate
        if (settings.isLongClickShare == true) setupContextMenu() else contextMenu = null
        val contextMenuCallback: ((GeckoSession, Int, Int, GeckoSession.ContentDelegate.ContextElement) -> Unit)? =
            if (contextMenu != null) {
                { _, _, _, el -> contextMenu?.onContextMenu(el) }
            } else null
        session.contentDelegate = PeelContentDelegate(
            host = this,
            onDownload = { response -> downloadHandler.onExternalResponse(response) },
            onContextMenu = contextMenuCallback,
        )
        session.progressDelegate = PeelProgressDelegate(this)
        session.permissionDelegate = permissionDelegate
        session.promptDelegate = promptDelegate

        val nestedView = geckoView as? NestedGeckoView
        if (nestedView != null) {
            session.scrollDelegate = object : GeckoSession.ScrollDelegate {
                override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                    nestedView.updateScrollPosition(scrollY)
                }
            }
        }

        val runtime = GeckoRuntimeProvider.getRuntime(this)
        session.open(runtime)
        session.setActive(true)
        runtime.webExtensionController.setTabActive(session, true)
        geckoView?.setSession(session)
        geckoView?.coverUntilFirstPaint(themeBackgroundColor)
        sessionExtensionActions.attach(session)
    }

    private fun launchSessionExtensionsAndLoad(settings: WebAppSettings, url: String) {
        sessionSetupJob = lifecycleScope.launch {
            loadURL(url)
            if (settings.isDynamicStatusBar == true) {
                val ext = GeckoRuntimeProvider.ensureThemeColorExtension(applicationContext)
                val delegate = geckoSession?.contentDelegate as? PeelContentDelegate
                if (ext != null && delegate != null && geckoSession != null) {
                    delegate.setupThemeColorExtension(ext, geckoSession!!)
                }
            }
            setupMediaPlayback(settings)
            autoReloadController.start(settings)
        }
    }

    private fun setupMediaPlayback(settings: WebAppSettings) {
        if (settings.isAllowMediaPlaybackInBackground != true) return
        val session = geckoSession ?: return
        val manager = MediaPlaybackManager(this)
        manager.attach(session, webapp.title, webapp.resolveIcon(), webapp.uuid)
        mediaPlaybackManager = manager
    }

    private fun setupContextMenu() {
        contextMenu = BrowserContextMenu(
            activity = this,
            downloadHandler = downloadHandler,
            onExternalIntent = ::startExternalIntent,
            onOpenInPeel = ::openInPeel,
            onOpenInBestPeelMatch = ::openInBestPeelMatch,
            bestPeelMatchIcon = ::bestPeelMatchIcon,
            onToast = ::showToast,
        )
    }

    private fun applyVisualSettings(settings: WebAppSettings) {
        applyWindowFlags(settings)
        setupPullToRefresh(settings)
        applyColorScheme()
        if (settings.isShowFullscreen == true) systemBarController.hide() else systemBarController.show(
            false
        )
    }

    private fun bindViews() {
        findViewById<View>(R.id.browser_root)?.setBackgroundColor(themeBackgroundColor)
        findViewById<View>(R.id.browserContent)?.setBackgroundColor(themeBackgroundColor)
        launchOverlay?.let { launchOverlayController.attach(it, themeBackgroundColor) }
        launchOverlayController.arm { systemBarController.suppressNextAnimation = true }
    }

    private fun resetHistoryAfterAuthReturn() {
        val session = geckoSession ?: return
        session.purgeHistory()
        historyPurged = true
        canGoBack = false
    }

    private fun handleStartupAuthHistoryReset(url: String) {
        if (!isStartupAuthTrackingActive) return
        startupAuthReturnTracker.onLocationChange(url)
        if (startupAuthReturnTracker.consumeShouldResetHistory()) {
            resetHistoryAfterAuthReturn()
            isStartupAuthTrackingActive = false
        }
    }

    private fun closeStartupAuthTrackingIfInitialBaseLoaded() {
        if (!isStartupAuthTrackingActive) return
        if (currentUrl.normalizedHost() == webapp.baseUrl.normalizedHost()) {
            isStartupAuthTrackingActive = false
        }
    }


    override fun findPeelAppMatches(url: String): List<WebApp> {
        val currentUuid = webappUuid ?: return emptyList()
        val targetHost = url.normalizedHost() ?: return emptyList()
        if (targetHost == webapp.baseUrl.normalizedHost()) return emptyList()
        val pending = DataManager.instance.pendingDeleteUuids
        return cachedPeelApps.filter { app ->
            app.uuid != currentUuid &&
                    app.uuid !in pending &&
                    app.baseUrl.normalizedHost() == targetHost
        }
    }

    private fun bestPeelMatchIcon(url: String): Bitmap? {
        return ExternalLinkMenu.bestPeelMatch(cachedPeelApps, url, webappUuid)?.resolveIcon()
    }

    private fun openInBestPeelMatch(url: String) {
        val match = ExternalLinkMenu.bestPeelMatch(cachedPeelApps, url, webappUuid) ?: return
        BrowserLauncher.launch(match, this, url)
    }

    private fun openInPeel(url: String) {
        ExternalLinkMenu.openInPeelPicker(this, url, webappUuid)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (canGoBack) {
                        geckoSession?.goBack()
                        return
                    }
                    if (launchedFromMenu) {
                        startActivity(
                            Intent(this@BrowserActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                        )
                    }
                    finishAndRemoveTask()
                }
            },
        )
    }

    private fun sharedUrlFromIntent(): String? =
        intent.getStringExtra(Const.INTENT_TARGET_URL)

    private fun applyTaskSnapshotProtection() {
        val shouldProtect = effectiveSettings.isBiometricProtection == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!shouldProtect)
        }

        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(webapp.title, webapp.resolveIcon()))
    }

    companion object {
        private const val UI_ANIMATION_DURATION_MS = 300L
        private const val OVERLAY_HIDE_FALLBACK_MS = 800L

        private val liveInstances = mutableSetOf<BrowserActivity>()

        fun hasLiveInstances(): Boolean = liveInstances.isNotEmpty()

        fun finishByUuid(uuid: String) {
            liveInstances.filter { it.webappUuid == uuid }.forEach { it.finish() }
        }

        fun finishAll() {
            liveInstances.toList().forEach { it.finish() }
        }
    }
}
