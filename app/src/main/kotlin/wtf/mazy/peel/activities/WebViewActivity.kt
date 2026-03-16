package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebView
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
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.media.MediaJsBridge
import wtf.mazy.peel.media.MediaPlaybackManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.FloatingControlsView
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.webview.AutoReloadController
import wtf.mazy.peel.ui.webview.BiometricUnlockController
import wtf.mazy.peel.ui.webview.LaunchOverlayController
import wtf.mazy.peel.ui.webview.SystemBarController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.DateUtils.convertStringToCalendar
import wtf.mazy.peel.util.DateUtils.isInInterval
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.WebViewLauncher
import wtf.mazy.peel.util.buildUserAgent
import wtf.mazy.peel.util.domainAffinity
import wtf.mazy.peel.util.shortLabel
import wtf.mazy.peel.webview.ChromeClientHost
import wtf.mazy.peel.webview.DownloadHandler
import wtf.mazy.peel.webview.ImageCache
import wtf.mazy.peel.webview.NavigationStartPoint
import wtf.mazy.peel.webview.PeelWebChromeClient
import wtf.mazy.peel.webview.PeelWebViewClient
import wtf.mazy.peel.webview.PermissionResult
import wtf.mazy.peel.webview.WebViewClientHost
import wtf.mazy.peel.webview.WebViewContextMenu
import java.util.Calendar

open class WebViewActivity : AppCompatActivity(), WebViewClientHost, ChromeClientHost {
    override var webappUuid: String? = null
    var webView: WebView? = null
        private set

    private var progressBar: ProgressBar? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    override var currentlyReloading = true
    private var customHeaders: Map<String, String>? = null
    override var filePathCallback: ValueCallback<Array<Uri?>?>? = null
    private val filePickerLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            if (result?.resultCode == RESULT_OK) {
                callback.onReceiveValue(extractUris(result.data))
            } else {
                callback.onReceiveValue(null)
            }
            filePathCallback = null
        }
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.isNotEmpty() && results.values.all { it }
            pendingPermissionCallback?.invoke(allGranted)
            pendingPermissionCallback = null
        }

    private lateinit var _navigationStartPoint: NavigationStartPoint
    override val navigationStartPoint: NavigationStartPoint
        get() = _navigationStartPoint

    private val webapp: WebApp
        get() = DataManager.instance.getWebApp(webappUuid!!)!!

    private var floatingControls: FloatingControlsView? = null
    private val downloadHandler = DownloadHandler(
        activity = this,
        getWebView = { webView },
        getBaseUrl = { webapp.baseUrl },
        getProgressBar = { progressBar },
        onDownloadComplete = {},
    )
    private lateinit var imageCache: ImageCache
    private lateinit var peelWebViewClient: PeelWebViewClient
    private var peelWebChromeClient: PeelWebChromeClient? = null

    private var pageLoadHandled = false
    private var mediaPlaybackManager: MediaPlaybackManager? = null
    private var cachedSettings: WebAppSettings? = null
    private var isStartupComplete = false
    private var ephemeralSandboxId: String? = null
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
                onPageFullyLoaded()
            },
            onFailure = { finish() },
        )
    }

    private val autoReloadController = AutoReloadController(
        mainHandler = mainHandler,
        onReload = {
            currentlyReloading = true
            webView?.reload()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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

        imageCache = ImageCache(cacheDir = cacheDir, getWebView = { webView })

        val sandboxId = WebViewLauncher.resolveSandboxId(webapp)
        if (sandboxId != null) {
            if (!SandboxManager.initDataDirectorySuffix(sandboxId)) {
                finishAndRemoveTask()
                return
            }
            if (WebViewLauncher.isEphemeralSandbox(webapp)) {
                ephemeralSandboxId = sandboxId
                SandboxManager.wipeSandboxStorage(sandboxId)
            }
        }

        _navigationStartPoint = NavigationStartPoint(webapp.baseUrl)
        cachedSettings = DataManager.instance.resolveEffectiveSettings(webapp)
        applyTaskSnapshotProtection()
        setupWebView()
        biometricController.registerReceiver()
        biometricController.showPromptIfNeeded(
            effectiveSettings.isBiometricProtection == true,
        ) { launchOverlayController.arm { systemBarController.suppressNextAnimation = true } }

        setupBackNavigation()
        isStartupComplete = true
    }

    override fun onResume() {
        super.onResume()
        val uuid = webappUuid ?: return
        ensureDataReady(uuid, forceReload = true) {
            applyResumedState()
        }
    }

    private fun applyResumedState() {
        if (!isStartupComplete) return
        val uuid = webappUuid ?: return
        if (DataManager.instance.getWebApp(uuid) == null) return
        cachedSettings = DataManager.instance.resolveEffectiveSettings(webapp)
        webView?.onResume()
        webView?.resumeTimers()
        mediaPlaybackManager?.setBackground(false)
        if (webView != null) setDarkModeIfNeeded()

        if (effectiveSettings.isShowNotification == true && floatingControls == null) {
            floatingControls = FloatingControlsView(
                parent = findViewById(R.id.webview_root),
                webappUuid = uuid,
                getWebView = { webView },
                onHome = {
                    webView?.let { navigationStartPoint.reset(it) }
                    loadURL(webapp.baseUrl)
                },
            )
        }

        biometricController.showPromptIfNeeded(
            effectiveSettings.isBiometricProtection == true,
        ) { launchOverlayController.arm { systemBarController.suppressNextAnimation = true } }

        autoReloadController.start(effectiveSettings)
    }

    override fun onPause() {
        super.onPause()
        if (!isStartupComplete) return
        val settings = effectiveSettings
        floatingControls?.remove()
        floatingControls = null

        if (settings.isAllowMediaPlaybackInBackground == true) {
            mediaPlaybackManager?.setBackground(true)
        } else {
            webView?.evaluateJavascript(
                "document.querySelectorAll('audio').forEach(x => x.pause());" +
                        "document.querySelectorAll('video').forEach(x => x.pause());",
                null,
            )
            webView?.onPause()
            webView?.pauseTimers()
        }

        if (settings.isClearCache == true) webView?.clearCache(true)

        autoReloadController.stop()
    }

    override fun onStop() {
        super.onStop()
        biometricController.onStop()
    }

    override fun onDestroy() {
        launchOverlayController.release()
        biometricController.unregisterReceiver()
        systemBarController.release()
        mediaPlaybackManager?.release()
        mediaPlaybackManager = null
        webView?.destroy()
        webView = null
        if (isFinishing) {
            ephemeralSandboxId?.let { SandboxManager.wipeSandboxStorage(it) }
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setDarkModeIfNeeded()
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
        if (SandboxManager.currentSlotId != null && uuid != null) {
            lifecycleScope.launch {
                DataManager.instance.ensureWebAppLoaded(uuid, forceReload = forceReload)
                cachedPeelApps = DataManager.instance.queryAllWebApps()
                if (!isFinishing && !isDestroyed) action()
            }
        } else {
            action()
            lifecycleScope.launch {
                cachedPeelApps = DataManager.instance.queryAllWebApps()
            }
        }
    }

    private fun applyLoadedWebAppIntent(newUuid: String) {
        if (isFinishing || isDestroyed) return
        if (DataManager.instance.getWebApp(newUuid) == null) return
        webappUuid = newUuid
        _navigationStartPoint = NavigationStartPoint(webapp.baseUrl)
        cachedSettings = DataManager.instance.resolveEffectiveSettings(webapp)
        customHeaders = buildCustomHeaders(effectiveSettings)
        pageLoadHandled = false
        biometricController.resetForSwap()
        systemBarController.resetForSwap()
        mediaPlaybackManager?.release()
        mediaPlaybackManager = null
        setupMediaPlayback(effectiveSettings)
        applyTaskSnapshotProtection()
        loadURL(sharedUrlFromIntent() ?: webapp.baseUrl)
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

    override fun showHttpAuthDialog(handler: HttpAuthHandler, host: String?, realm: String?) {
        var passwordInput: TextInputEditText? = null
        val dp8 = (resources.displayMetrics.density * 8).toInt()
        showInputDialogRaw(
            InputDialogConfig(
                titleRes = R.string.setting_basic_auth,
                hintRes = R.string.username,
                allowEmpty = true,
                message = "Host: ${host ?: "-"}\nRealm: ${realm ?: "-"}",
                onCancel = { handler.cancel() },
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
            handler.proceed(
                usernameInput.text.toString(),
                passwordInput?.text?.toString().orEmpty(),
            )
        }
    }

    override val isForceDarkActive: Boolean
        get() = getDelegate().localNightMode == AppCompatDelegate.MODE_NIGHT_YES

    @SuppressLint("RequiresFeature")
    override fun setDarkModeIfNeeded() {
        val settings = effectiveSettings
        val isInDarkModeTimespan =
            if (settings.isUseTimespanDarkMode == true) {
                val begin = convertStringToCalendar(settings.timespanDarkModeBegin)
                val end = convertStringToCalendar(settings.timespanDarkModeEnd)
                if (begin != null && end != null) isInInterval(begin, Calendar.getInstance(), end)
                else false
            } else false

        val needsForcedDarkMode =
            isInDarkModeTimespan ||
                    (settings.isUseTimespanDarkMode != true && settings.isForceDarkMode == true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isAlgorithmicDarkeningSupported =
                WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
            val currentWebView = webView ?: return

            if (needsForcedDarkMode) {
                currentWebView.setBackgroundColor(Color.BLACK)
                @Suppress("DEPRECATION")
                currentWebView.isForceDarkAllowed = true
                getDelegate().localNightMode = AppCompatDelegate.MODE_NIGHT_YES
                if (isAlgorithmicDarkeningSupported) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(currentWebView.settings, true)
                }
            } else {
                currentWebView.setBackgroundColor(Color.WHITE)
                getDelegate().localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                if (isAlgorithmicDarkeningSupported) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(currentWebView.settings, false)
                }
            }
        }
    }

    override fun loadURL(url: String) {
        var finalUrl = url
        if (url.startsWith("http://") && effectiveSettings.isAlwaysHttps == true) {
            finalUrl = url.replaceFirst("http://", "https://")
        }
        webView?.loadUrl(finalUrl, customHeaders ?: emptyMap())
    }

    override fun finishActivity() = finish()

    override fun showToast(message: String) {
        NotificationUtils.showToast(this, message)
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
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun updateStatusBarColor(color: Int) {
        systemBarController.update(
            color,
            launchOverlayController.isVisible,
            UI_ANIMATION_DURATION_MS
        )
    }

    override fun onPageStarted() {
        pageLoadHandled = false
        launchOverlayController.release()
        peelWebChromeClient?.clearPagePermissions()
        mediaPlaybackManager?.injectPolyfill()
    }

    override fun onPageFullyLoaded() {
        if (pageLoadHandled || biometricController.isPromptActive) return
        pageLoadHandled = true
        if (launchOverlayController.isVisible) {
            launchOverlayController.hideWhenReady(webView)
        }
        webView?.let { peelWebViewClient.extractDynamicBarColor(it) }
        mediaPlaybackManager?.injectObserver()
    }

    override fun startExternalIntent(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
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
        filePickerLauncher.launch(intent)
        return true
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

    override fun showPermissionDialog(message: String, onResult: (PermissionResult) -> Unit) {
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
            .setOnCancelListener { onResult(PermissionResult.DENY) }
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWebView() {
        val settings = effectiveSettings
        window.setBackgroundDrawable(themeBackgroundColor.toDrawable())
        setContentView(R.layout.full_webview)
        bindViews()
        systemBarController.attach(
            rootView = findViewById(R.id.webview_root),
            applyDynamicColor = settings.isDynamicStatusBar == true,
        )
        applyWindowFlags(settings)
        setupPullToRefresh(settings)
        webView?.buildUserAgent()
        if (settings.isShowFullscreen == true) systemBarController.hide() else systemBarController.show(
            false
        )
        configureWebViewSettings(settings)
        setDarkModeIfNeeded()
        configureCookies(settings)
        configureZoom(settings)

        customHeaders = buildCustomHeaders(settings)

        peelWebChromeClient = PeelWebChromeClient(this)
        webView?.webChromeClient = peelWebChromeClient

        loadURL(sharedUrlFromIntent() ?: webapp.baseUrl)

        setupLongClickShare(settings)
        webView?.let { downloadHandler.install(it) }
        setupMediaPlayback(settings)
    }

    @SuppressLint("JavascriptInterface")
    private fun setupMediaPlayback(settings: WebAppSettings) {
        if (settings.isAllowMediaPlaybackInBackground != true) return
        val view = webView ?: return
        val manager = MediaPlaybackManager(this)
        manager.attach(view, webapp.title, webapp.resolveIcon(), webapp.uuid)
        view.addJavascriptInterface(MediaJsBridge(manager), MediaJsBridge.JS_INTERFACE_NAME)
        mediaPlaybackManager = manager
    }

    private fun applyWindowFlags(settings: WebAppSettings) {
        if (settings.isShowFullscreen == true) {
            findViewById<View>(R.id.webview_root)?.fitsSystemWindows = false
            findViewById<View>(R.id.webviewActivity)?.fitsSystemWindows = false
        }
        if (settings.isKeepAwake == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (settings.isDisableScreenshots == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun bindViews() {
        findViewById<View>(R.id.webview_root)?.setBackgroundColor(themeBackgroundColor)
        findViewById<View>(R.id.webviewActivity)?.setBackgroundColor(themeBackgroundColor)
        webView = findViewById(R.id.webview)
        val overlay = findViewById<View>(R.id.webviewLaunchOverlay)
        launchOverlayController.attach(overlay, themeBackgroundColor)
        launchOverlayController.arm { systemBarController.suppressNextAnimation = true }
        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
    }

    private fun setupPullToRefresh(settings: WebAppSettings) {
        if (settings.isPullToRefresh == true) {
            swipeRefreshLayout?.apply {
                isEnabled = true
                setOnChildScrollUpCallback { _, _ ->
                    webView?.let { it.scrollY > 0 || it.canScrollVertically(-1) } ?: true
                }
                setOnRefreshListener {
                    webView?.reload()
                    isRefreshing = false
                }
            }
        } else {
            swipeRefreshLayout?.isEnabled = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings(settings: WebAppSettings) {
        webView?.apply {
            peelWebViewClient = PeelWebViewClient(this@WebViewActivity)
            webViewClient = peelWebViewClient
            this.settings.apply {
                safeBrowsingEnabled = settings.isSafeBrowsing == true
                domStorageEnabled = true
                allowFileAccess = false
                blockNetworkLoads = false
                javaScriptEnabled = settings.isAllowJs == true
                if (settings.isBlockImages == true) blockNetworkImage = true
                if (settings.isRequestDesktop == true) {
                    userAgentString = Const.DESKTOP_USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
            }
        }
    }

    private fun configureCookies(settings: WebAppSettings) {
        CookieManager.getInstance().apply {
            setAcceptCookie(settings.isAllowCookies == true)
            setAcceptThirdPartyCookies(webView, settings.isAllowThirdPartyCookies == true)
        }
    }

    private fun configureZoom(settings: WebAppSettings) {
        webView?.apply {
            scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
            isScrollbarFadingEnabled = false
            if (settings.isEnableZooming == true) {
                this.settings.apply {
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
            }
        }
    }

    private fun setupLongClickShare(settings: WebAppSettings) {
        if (settings.isLongClickShare != true) return
        val wv = webView ?: return
        WebViewContextMenu(
            activity = this,
            getWebView = { webView },
            imageCache = imageCache,
            onExternalIntent = ::startExternalIntent,
            onOpenInPeel = ::openInPeel,
            onOpenInBestPeelMatch = ::openInBestPeelMatch,
            bestPeelMatchIcon = ::bestPeelMatchIcon,
            onToast = ::showToast,
        ).install(wv)
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
            .associateWith { domainAffinity(it.baseUrl, url) }
        val topScore = scores.values.maxOrNull() ?: return null
        if (topScore <= 1) return null
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
                    domainAffinity(it.baseUrl, url)
                }.thenBy { it.title })
            if (apps.isEmpty()) return@launch

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
            MaterialAlertDialogBuilder(this@WebViewActivity)
                .setTitle(R.string.open_in_peel)
                .setAdapter(adapter) { _, position -> launchWebApp(apps[position], url) }
                .show()
        }
    }

    private fun launchWebApp(webapp: WebApp, url: String) {
        WebViewLauncher.startWebView(webapp, this, url)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val steps = getBackSteps()
                    when {
                        steps > 0 -> webView?.goBackOrForward(-steps)
                        else -> finishAndRemoveTask()
                    }
                }
            },
        )
    }

    private fun getBackSteps(): Int {
        val wv = webView ?: return 0
        if (!wv.canGoBack()) return 0

        val history = wv.copyBackForwardList()
        val currentIndex = history.currentIndex
        if (currentIndex <= 0) return 0

        return if (navigationStartPoint.canGoBackFrom(currentIndex)) 1 else 0
    }

    private fun sharedUrlFromIntent(): String? =
        intent.getStringExtra(Const.INTENT_TARGET_URL)

    private fun extractUris(intent: Intent?): Array<Uri?>? =
        intent?.data?.let { arrayOf(it) }
            ?: intent?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }

    private fun buildCustomHeaders(settings: WebAppSettings): Map<String, String> {
        val extraHeaders = mutableMapOf("DNT" to "1", "X-REQUESTED-WITH" to "")
        settings.customHeaders?.forEach { (key, value) ->
            if (key.equals("User-Agent", ignoreCase = true)) {
                webView?.settings?.userAgentString = value
            } else {
                extraHeaders[key] = value
            }
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
    }
}
