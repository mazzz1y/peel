package wtf.mazy.peel.activities

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.Loader
import org.mozilla.geckoview.GeckoSessionSettings
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.BrowserContextMenu
import wtf.mazy.peel.browser.DownloadHandler
import wtf.mazy.peel.browser.DownloadService
import wtf.mazy.peel.browser.ExternalLinkResult
import wtf.mazy.peel.browser.MenuDialogHelper
import wtf.mazy.peel.browser.PeelContentDelegate
import wtf.mazy.peel.browser.PeelNavigationDelegate
import wtf.mazy.peel.browser.PeelPermissionDelegate
import wtf.mazy.peel.browser.PeelProgressDelegate
import wtf.mazy.peel.browser.PeelPromptDelegate
import wtf.mazy.peel.browser.PermissionResult
import wtf.mazy.peel.browser.SessionHost
import wtf.mazy.peel.browser.StartupAuthReturnTracker
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.gecko.NestedGeckoView
import wtf.mazy.peel.gecko.VerticalSwipeRefreshLayout
import wtf.mazy.peel.media.MediaPlaybackManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.FloatingControlsView
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.ui.browser.AutoReloadController
import wtf.mazy.peel.ui.browser.BiometricUnlockController
import wtf.mazy.peel.ui.browser.LaunchOverlayController
import wtf.mazy.peel.ui.browser.SystemBarController
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.extensions.ExtensionPickerDialog
import wtf.mazy.peel.ui.extensions.SessionExtensionActions
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.normalizedHost
import wtf.mazy.peel.util.shortLabel

class BrowserActivity : AppCompatActivity(), SessionHost {
    var webappUuid: String? = null

    private var geckoView: NestedGeckoView? = null
    private var geckoSession: GeckoSession? = null
    private val sessionExtensionActions by lazy {
        SessionExtensionActions(this) { hasExtensions ->
            if (hasExtensions) rebuildFloatingControls()
        }
    }

    private var progressBar: ProgressBar? = null
    private var swipeRefreshLayout: VerticalSwipeRefreshLayout? = null
    override var currentlyReloading = true
    private var customHeaders: Map<String, String>? = null
    override var filePathCallback: ((Array<Uri>?) -> Unit)? = null
    private var historyPurged = false
    override var canGoBack = false
        set(value) {
            if (historyPurged && value) return
            field = value
        }
    var currentUrl = ""
    override var lastLoadedUrl = ""

    private val launchedFromMenu by lazy {
        intent.getBooleanExtra(Const.INTENT_LAUNCHED_FROM_MENU, false)
    }

    private val filePickerLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            if (result?.resultCode != RESULT_OK) {
                PeelPromptDelegate.consumeCaptureUri()
                callback.invoke(null)
                return@registerForActivityResult
            }
            val contentUris = extractUris(result.data)
            if (contentUris.isNullOrEmpty()) {
                val captured = PeelPromptDelegate.consumeCaptureUri()
                callback.invoke(captured?.let { arrayOf(it) })
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val fileUris = withContext(Dispatchers.IO) { resolveToFileUris(contentUris) }
                if (fileUris.isNotEmpty()) {
                    callback.invoke(fileUris)
                } else {
                    val captured = PeelPromptDelegate.consumeCaptureUri()
                    callback.invoke(captured?.let { arrayOf(it) })
                }
            }
        }
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.isNotEmpty() && results.values.all { it }
            pendingPermissionCallback?.invoke(allGranted)
            pendingPermissionCallback = null
        }

    private val webapp: WebApp
        get() = DataManager.instance.getWebApp(webappUuid!!)!!

    private var floatingControls: FloatingControlsView? = null
    private lateinit var downloadHandler: DownloadHandler
    private lateinit var navigationDelegate: PeelNavigationDelegate
    private lateinit var permissionDelegate: PeelPermissionDelegate
    private lateinit var promptDelegate: PeelPromptDelegate
    private var contextMenu: BrowserContextMenu? = null

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: return
            val uriString = intent.getStringExtra(DownloadService.EXTRA_URI) ?: return
            val mimeType = intent.getStringExtra(DownloadService.EXTRA_MIME_TYPE)
            showDownloadSnackbar(fileName, Uri.parse(uriString), mimeType)
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
        setContentView(R.layout.activity_browser)
        findViewById<View>(R.id.launchOverlay)?.let { overlay ->
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
            parent = findViewById(R.id.browser_root),
            webappUuid = uuid,
            onHome = { loadURL(webapp.baseUrl) },
            onReload = ::reloadCurrentPage,
            onExtensions = if (sessionExtensionActions.hasExtensions)
                ({ ExtensionPickerDialog.show(this, sessionExtensionActions) }) else null,
            getCurrentUrl = { currentUrl },
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

        if (settings.isClearCache == true) {
            val runtime = GeckoRuntimeProvider.getRuntime(this)
            runtime.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES)
        }

        biometricController.onStop()
    }

    override fun onDestroy() {
        liveInstances.remove(this)
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

    override val hostProgressBar: ProgressBar?
        get() = progressBar

    override var hostOrientation: Int
        get() = requestedOrientation
        set(value) {
            requestedOrientation = value
        }

    override val hostWindow: android.view.Window
        get() = window

    override fun showHttpAuthDialog(
        onResult: (username: String, password: String) -> Unit,
        onCancel: () -> Unit,
        host: String?,
        realm: String?,
    ) {
        var passwordInput: TextInputEditText? = null
        val dp8 = (resources.displayMetrics.density * 8).toInt()
        showInputDialogRaw(
            InputDialogConfig(
                titleRes = R.string.setting_basic_auth,
                hintRes = R.string.username,
                allowEmpty = true,
                message = "Host: ${host ?: "-"}\nRealm: ${realm ?: "-"}",
                onCancel = { onCancel() },
                extraContent = { container ->
                    val passwordLayout = TextInputLayout(container.context).apply {
                        hint = getString(R.string.password)
                        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp8 }
                    }
                    passwordInput = TextInputEditText(passwordLayout.context).apply {
                        inputType =
                            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        isSingleLine = true
                    }
                    passwordLayout.addView(passwordInput)
                    container.addView(passwordLayout)
                },
            ),
        ) { usernameInput, _ ->
            onResult(
                usernameInput.text.toString(),
                passwordInput?.text?.toString().orEmpty(),
            )
        }
    }

    private fun applyColorScheme() {
        val settings = effectiveSettings
        val scheme = settings.colorScheme ?: WebAppSettings.COLOR_SCHEME_AUTO

        val nightMode = when (scheme) {
            WebAppSettings.COLOR_SCHEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            WebAppSettings.COLOR_SCHEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        getDelegate().localNightMode = nightMode

    }

    override fun loadURL(url: String) {
        var finalUrl = url
        if (url.startsWith("http://") && effectiveSettings.isAlwaysHttps == true) {
            finalUrl = url.replaceFirst("http://", "https://")
        }
        val headers = customHeaders
        if (headers.isNullOrEmpty()) {
            geckoSession?.loadUri(finalUrl)
        } else {
            geckoSession?.load(
                Loader()
                    .uri(finalUrl)
                    .additionalHeaders(headers)
                    .headerFilter(GeckoSession.HEADER_FILTER_UNRESTRICTED_UNSAFE)
            )
        }
    }

    private fun showToast(message: String) {
        NotificationUtils.showToast(this, message)
    }

    private fun showDownloadSnackbar(fileName: String, uri: Uri, mimeType: String?) {
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.site_not_found)
            .setMessage(getString(R.string.connection_error, description))
            .setPositiveButton(R.string.retry) { _, _ -> loadURL(url) }
            .setNegativeButton(if (canGoBack) R.string.back else R.string.exit) { _, _ ->
                if (canGoBack) geckoSession?.goBack() else finish()
            }
            .setCancelable(false)
            .show()
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
        closeStartupAuthTrackingIfInitialBaseLoaded()
        navigationDelegate.onInitialPageLoaded()
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

    private fun buildIntentForUrl(url: String): Intent? {
        return try {
            if (url.startsWith("intent://") || url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, url.toUri())
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun startExternalIntent(uri: Uri) {
        val url = uri.toString()
        val intent = buildIntentForUrl(url)
        if (intent == null) {
            showToast(getString(R.string.no_app_found))
            return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val fallback = buildIntentForUrl(url)
                ?.getStringExtra("browser_fallback_url")
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                loadURL(fallback)
            } else {
                showToast(getString(R.string.no_app_found))
            }
        } catch (_: Exception) {
            showToast(getString(R.string.no_app_found))
        }
    }

    override val themeBackgroundColor: Int
        get() {
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            return tv.data
        }

    override fun runOnUi(action: Runnable) = runOnUiThread(action)

    override fun launchFilePicker(intent: Intent?): Boolean {
        val safeIntent = intent ?: return false
        return try {
            filePickerLauncher.launch(safeIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun hideSystemBars() {
        systemBarController.hide()
    }

    override fun showSystemBars() {
        systemBarController.show(effectiveSettings.isShowFullscreen == true)
    }

    override fun requestOsPermissions(
        permissions: Array<String>,
        onResult: (granted: Boolean) -> Unit,
    ) {
        pendingPermissionCallback = onResult
        permissionLauncher.launch(permissions)
    }

    override fun hasPermissions(vararg permissions: String): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun showPermissionDialog(message: CharSequence, onResult: (PermissionResult) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_prompt_allow) { dialog, _ ->
                dialog.dismiss()
                onResult(PermissionResult.ALLOW)
            }
            .setNegativeButton(R.string.permission_prompt_deny) { dialog, _ ->
                dialog.dismiss()
                onResult(PermissionResult.DENY)
            }
            .show()
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
        geckoView?.resetScrollPosition()
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

        val nestedView = geckoView
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

    private fun createSession(settings: WebAppSettings): GeckoSession {
        val contextId = webapp.resolveContextId()

        val sessionSettings = GeckoSessionSettings.Builder()
            .allowJavascript(settings.isAllowJs == true)
            .apply {
                if (settings.isRequestDesktop == true) {
                    userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                }
                if (contextId != null) contextId(contextId)
                usePrivateMode(webapp.resolvePrivateMode())
            }
            .build()

        val session = GeckoSession(sessionSettings)
        session.settings.useTrackingProtection =
            settings.isSafeBrowsing != null && settings.isSafeBrowsing != WebAppSettings.TRACKER_PROTECTION_NONE

        return session
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
        customHeaders = buildCustomHeaders(settings)
        applyWindowFlags(settings)
        setupPullToRefresh(settings)
        applyColorScheme()
        if (settings.isShowFullscreen == true) systemBarController.hide() else systemBarController.show(
            false
        )
    }

    private fun applyWindowFlags(settings: WebAppSettings) {
        if (settings.isKeepAwake == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (settings.isDisableScreenshots == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun bindViews() {
        findViewById<View>(R.id.browser_root)?.setBackgroundColor(themeBackgroundColor)
        findViewById<View>(R.id.browserContent)?.setBackgroundColor(themeBackgroundColor)
        geckoView = findViewById(R.id.geckoview)
        val overlay = findViewById<View>(R.id.launchOverlay)
        launchOverlayController.attach(overlay, themeBackgroundColor)
        launchOverlayController.arm { systemBarController.suppressNextAnimation = true }
        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
    }

    private fun setupPullToRefresh(settings: WebAppSettings) {
        if (settings.isPullToRefresh == true) {
            swipeRefreshLayout?.apply {
                isEnabled = true
                setOnRefreshListener {
                    reloadCurrentPage()
                    isRefreshing = false
                }
            }
        } else {
            swipeRefreshLayout?.isEnabled = false
        }
    }

    private fun reloadCurrentPage() {
        val url = currentUrl
        if (url.isNotEmpty() && customHeaders?.isNotEmpty() == true) {
            loadURL(url)
        } else {
            geckoSession?.reload()
        }
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

    override fun showExternalLinkMenu(
        url: String,
        onResult: (ExternalLinkResult) -> Unit,
    ) {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun dismiss(result: ExternalLinkResult) {
            dialog?.dismiss()
            onResult(result)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                MenuDialogHelper.buildHeader(
                    this@BrowserActivity,
                    null,
                    MenuDialogHelper.displayUrl(url)
                )
            )
            addView(MenuDialogHelper.buildDivider(this@BrowserActivity))
            val icon = bestPeelMatchIcon(url)
            val iconClick = if (icon != null) {
                { dismiss(ExternalLinkResult.OpenInPeelApp { openInBestPeelMatch(url) }) }
            } else null
            addView(
                MenuDialogHelper.buildActionRow(
                    this@BrowserActivity,
                    getString(R.string.open_in_peel),
                    icon,
                    iconClick
                ) {
                    dismiss(ExternalLinkResult.OpenInPeelApp { openInPeel(url) })
                })
            addView(
                MenuDialogHelper.buildActionRow(
                    this@BrowserActivity,
                    getString(R.string.open_in_current_session)
                ) {
                    dismiss(ExternalLinkResult.LOAD_HERE)
                })
            addView(
                MenuDialogHelper.buildActionRow(
                    this@BrowserActivity,
                    getString(R.string.open_in_system)
                ) {
                    dismiss(ExternalLinkResult.OPEN_IN_SYSTEM)
                })
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setOnCancelListener {
                onResult(ExternalLinkResult.DISMISSED)
            }
            .show()
    }

    private fun bestPeelMatchIcon(url: String): Bitmap? {
        val currentUuid = webappUuid ?: return null
        return bestPeelMatch(url, currentUuid)?.resolveIcon()
    }

    private fun bestPeelMatch(url: String, currentUuid: String): WebApp? =
        bestPeelMatchFrom(cachedPeelApps, url, currentUuid)

    private fun bestPeelMatchFrom(
        apps: List<WebApp>,
        url: String,
        currentUuid: String,
    ): WebApp? {
        val scores = apps
            .filter { it.uuid != currentUuid }
            .associateWith { HostIdentity.affinity(it.baseUrl, url) }
        val topScore = scores.values.maxOrNull() ?: return null
        if (topScore <= HostIdentity.TLD_ONLY) return null
        val topMatches = scores.filterValues { it == topScore }
        if (topMatches.size != 1) return null
        return topMatches.keys.first()
    }

    private fun openInBestPeelMatch(url: String) {
        val currentUuid = webappUuid ?: return
        lifecycleScope.launch {
            val apps = DataManager.instance.queryAllWebApps()
            val match = bestPeelMatchFrom(apps, url, currentUuid) ?: return@launch
            launchWebApp(match, url)
        }
    }

    private fun openInPeel(url: String) {
        val currentUuid = webappUuid ?: return
        lifecycleScope.launch {
            val allApps = DataManager.instance.queryAllWebApps()
            val apps = allApps
                .filter { it.uuid != currentUuid }
                .sortedWith(compareByDescending<WebApp> {
                    HostIdentity.affinity(it.baseUrl, url)
                }.thenBy { it.title })
            if (apps.isEmpty()) {
                showToast(getString(R.string.no_web_apps_available))
                return@launch
            }

            val hasGroups = apps.any { it.groupUuid != null }
            val groupTitles = if (hasGroups) {
                apps.mapNotNull { it.groupUuid }.distinct()
                    .associateWith { DataManager.instance.queryGroup(it)?.title }
            } else emptyMap()

            val adapter = ListPickerAdapter(apps) { webapp, icon, name, detail ->
                name.text = webapp.title
                icon.setImageBitmap(webapp.resolveIcon())
                if (hasGroups) {
                    detail.text = webapp.groupUuid?.let { groupTitles[it] }
                        ?.let { shortLabel(it) } ?: getString(R.string.ungrouped)
                    detail.visibility = View.VISIBLE
                }
            }
            val dialog = MaterialAlertDialogBuilder(this@BrowserActivity)
                .setTitle(R.string.open_in_peel)
                .setAdapter(adapter) { _, position -> launchWebApp(apps[position], url) }
                .show()
            dialog.listView?.layoutParams?.height =
                MenuDialogHelper.dpToPx(this@BrowserActivity, MAX_PEEL_PICKER_HEIGHT_DP)
            dialog.listView?.requestLayout()
        }
    }

    private fun launchWebApp(webapp: WebApp, url: String) {
        BrowserLauncher.launch(webapp, this, url)
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

    private fun extractUris(intent: Intent?): Array<Uri>? =
        intent?.data?.let { arrayOf(it) }
            ?: intent?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }

    private fun resolveToFileUris(uris: Array<Uri>): Array<Uri> {
        val picksDir = java.io.File(cacheDir, "picks").apply { mkdirs() }
        picksDir.listFiles()?.forEach { it.delete() }
        return uris.mapNotNull { uri ->
            if (uri.scheme == "file") return@mapNotNull uri
            try {
                val mimeType = contentResolver.getType(uri)
                val ext = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimeType) ?: "bin"
                val dest = java.io.File.createTempFile("pick_", ".$ext", picksDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(dest)
            } catch (_: Exception) {
                null
            }
        }.toTypedArray()
    }

    private fun buildCustomHeaders(settings: WebAppSettings): Map<String, String> {
        val extraHeaders = mutableMapOf("X-Requested-With" to "")
        settings.customHeaders?.forEach { (key, value) ->
            extraHeaders[key] = value
        }
        return extraHeaders.toMap()
    }

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
        private const val MAX_PEEL_PICKER_HEIGHT_DP = 400f

        private val liveInstances = mutableSetOf<BrowserActivity>()

        fun finishByUuid(uuid: String) {
            liveInstances.filter { it.webappUuid == uuid }.forEach { it.finish() }
        }

        fun finishAll() {
            liveInstances.toList().forEach { it.finish() }
        }
    }
}
