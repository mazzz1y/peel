package wtf.mazy.peel.activities

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import wtf.mazy.peel.R
import wtf.mazy.peel.media.MediaJsBridge
import wtf.mazy.peel.media.MediaPlaybackManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.BiometricPromptHelper
import wtf.mazy.peel.ui.FloatingControlsView
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
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
    private var webviewLaunchOverlay: View? = null
    private var isLaunchOverlayVisible = true
    override var currentlyReloading = true
    private var customHeaders: Map<String, String>? = null
    override var filePathCallback: ValueCallback<Array<Uri?>?>? = null
    private var filePickerLauncher: ActivityResultLauncher<Intent?>? = null
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.isNotEmpty() && results.values.all { it }
            pendingPermissionCallback?.invoke(allGranted)
            pendingPermissionCallback = null
        }
    private var reloadHandler: Handler? = null
    override val navigationStartPoint by lazy { NavigationStartPoint(webapp.baseUrl) }

    private val webapp: WebApp
        get() = DataManager.instance.getWebApp(webappUuid!!)!!

    private var floatingControls: FloatingControlsView? = null
    private lateinit var downloadHandler: DownloadHandler
    private lateinit var imageCache: ImageCache
    private lateinit var peelWebViewClient: PeelWebViewClient
    private var peelWebChromeClient: PeelWebChromeClient? = null

    private var biometricPromptActive = false
    private var pageLoadHandled = false
    private var pendingOverlayHideFallback: Runnable? = null

    private var statusBarScrim: View? = null
    private var navigationBarScrim: View? = null
    private var currentBarColor: Int? = null
    private var barColorAnimator: ValueAnimator? = null
    private var suppressNextBarAnimation = false

    private var mediaPlaybackManager: MediaPlaybackManager? = null
    private var cachedSettings: WebAppSettings? = null
    private var isScreenStateReceiverRegistered = false
    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    clearBiometricUnlocks()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        if (SandboxManager.currentSlotId != null) DataManager.instance.loadAppData()
        if (webappUuid == null || DataManager.instance.getWebApp(webappUuid!!) == null) {
            NotificationUtils.showToast(this, getString(R.string.webapp_not_found))
            finishAndRemoveTask()
            return
        }

        initFilePickerLauncher()
        initDownloadHandler()

        val sandboxId = WebViewLauncher.resolveSandboxId(webapp)
        if (sandboxId != null) {
            if (!SandboxManager.initDataDirectorySuffix(sandboxId)) {
                finishAndRemoveTask()
                return
            }
            if (WebViewLauncher.isEphemeralSandbox(webapp)) {
                SandboxManager.wipeSandboxStorage(sandboxId)
            }
        }

        cachedSettings = webapp.effectiveSettings
        applyTaskSnapshotProtection()
        setupWebView()
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isScreenStateReceiverRegistered = true

        if (effectiveSettings.isBiometricProtection == true && !isBiometricUnlocked()) {
            showBiometricPrompt()
        }

        setupBackNavigation()
    }

    override fun onResume() {
        super.onResume()
        if (SandboxManager.currentSlotId != null) DataManager.instance.loadAppData()
        cachedSettings = webapp.effectiveSettings
        webView?.onResume()
        webView?.resumeTimers()
        mediaPlaybackManager?.setBackground(false)
        if (webView != null) setDarkModeIfNeeded()

        if (effectiveSettings.isShowNotification == true && floatingControls == null) {
            floatingControls = FloatingControlsView(
                parent = findViewById(R.id.webview_root),
                getWebView = { webView },
                onHome = {
                    webView?.let { navigationStartPoint.reset(it) }
                    loadURL(webapp.baseUrl)
                },
            )
        }

        if (effectiveSettings.isBiometricProtection == true && !isBiometricUnlocked()) {
            showBiometricPrompt()
        }

        if (effectiveSettings.isAutoReload == true) {
            reloadHandler = Handler(Looper.getMainLooper())
            scheduleAutoReload()
        }
    }

    override fun onPause() {
        super.onPause()
        val bgMedia = effectiveSettings.isAllowMediaPlaybackInBackground == true
        floatingControls?.remove()
        floatingControls = null

        if (bgMedia) {
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

        if (effectiveSettings.isClearCache == true) webView?.clearCache(true)

        reloadHandler?.removeCallbacksAndMessages(null)
        reloadHandler = null
    }

    override fun onStop() {
        super.onStop()
        clearBiometricUnlock()
    }

    override fun onDestroy() {
        pendingOverlayHideFallback?.let { mainHandler.removeCallbacks(it) }
        pendingOverlayHideFallback = null
        if (isScreenStateReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            isScreenStateReceiverRegistered = false
        }
        barColorAnimator?.cancel()
        barColorAnimator = null
        mediaPlaybackManager?.release()
        mediaPlaybackManager = null
        webView?.destroy()
        webView = null
        if (isFinishing) {
            val app = webappUuid?.let { DataManager.instance.getWebApp(it) }
            if (app != null) {
                val sandboxId = WebViewLauncher.resolveSandboxId(app)
                if (sandboxId != null && WebViewLauncher.isEphemeralSandbox(app)) {
                    SandboxManager.wipeSandboxStorage(sandboxId)
                }
            }
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

        if (DataManager.instance.getWebApp(newUuid) == null) return
        webappUuid = newUuid
        cachedSettings = webapp.effectiveSettings
        applyTaskSnapshotProtection()
        loadURL(sharedUrlFromIntent() ?: webapp.baseUrl)
    }

    override val webAppName: String
        get() = webapp.title

    override val effectiveSettings: WebAppSettings
        get() = cachedSettings ?: webapp.effectiveSettings

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

    private fun sharedUrlFromIntent(): String? =
        intent.getStringExtra(Const.INTENT_TARGET_URL)

    override fun finishActivity() = finish()

    override fun showToast(message: String) {
        NotificationUtils.showToast(this, message)
    }

    override fun showConnectionError(description: String, url: String) {
        if (isLaunchOverlayVisible) {
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
        if (isLaunchOverlayVisible || suppressNextBarAnimation) {
            applyBarColor(color)
            suppressNextBarAnimation = false
            return
        }
        val fromColor = currentBarColor ?: themeBackgroundColor
        if (fromColor == color) {
            applyBarColor(color)
            return
        }
        barColorAnimator?.cancel()
        barColorAnimator =
            ValueAnimator.ofArgb(fromColor, color).apply {
                duration = UI_ANIMATION_DURATION_MS
                addUpdateListener { applyBarColor(it.animatedValue as Int) }
                start()
            }
    }

    override fun onPageStarted() {
        pageLoadHandled = false
        pendingOverlayHideFallback?.let { mainHandler.removeCallbacks(it) }
        pendingOverlayHideFallback = null
        peelWebChromeClient?.clearPagePermissions()
        mediaPlaybackManager?.injectPolyfill()
    }

    override fun onPageFullyLoaded() {
        if (pageLoadHandled || biometricPromptActive) return
        pageLoadHandled = true
        if (isLaunchOverlayVisible) {
            hideLaunchOverlayWhenReady()
        }
        webView?.let { peelWebViewClient.extractDynamicBarColor(it) }
        mediaPlaybackManager?.injectObserver()
    }

    private fun hideLaunchOverlayWhenReady() {
        val view = webView
        if (view == null) {
            hideLaunchOverlayIfNeeded()
            return
        }

        var overlayHidden = false
        val fallback = Runnable {
            if (overlayHidden || isDestroyed) return@Runnable
            overlayHidden = true
            hideLaunchOverlayIfNeeded()
        }
        pendingOverlayHideFallback = fallback
        mainHandler.postDelayed(fallback, OVERLAY_HIDE_FALLBACK_MS)

        view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (overlayHidden || isDestroyed) return
                overlayHidden = true
                pendingOverlayHideFallback?.let { mainHandler.removeCallbacks(it) }
                pendingOverlayHideFallback = null
                hideLaunchOverlayIfNeeded()
            }
        })
    }

    private fun armLaunchOverlay() {
        webviewLaunchOverlay?.apply {
            alpha = 1f
            visibility = View.VISIBLE
        }
        isLaunchOverlayVisible = true
        suppressNextBarAnimation = true
    }

    private fun hideLaunchOverlayIfNeeded() {
        if (!isLaunchOverlayVisible) return
        isLaunchOverlayVisible = false
        webviewLaunchOverlay?.animate()?.alpha(0f)?.setDuration(UI_ANIMATION_DURATION_MS)
            ?.withEndAction {
                webviewLaunchOverlay?.visibility = View.GONE
            }?.start()
    }

    private fun applyBarColor(color: Int) {
        currentBarColor = color
        statusBarScrim?.setBackgroundColor(color)
        navigationBarScrim?.setBackgroundColor(color)
        val isLight = androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
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
        filePickerLauncher?.launch(intent) ?: return false
        return true
    }

    override fun hideSystemBars() {
        statusBarScrim?.visibility = View.GONE
        navigationBarScrim?.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun showSystemBars() {
        if (effectiveSettings.isShowFullscreen == true) return
        statusBarScrim?.visibility = View.VISIBLE
        navigationBarScrim?.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(true)
            window.insetsController?.apply {
                show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
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

    private fun initFilePickerLauncher() {
        filePickerLauncher =
            registerForActivityResult(
                StartActivityForResult(),
                ActivityResultCallback { result ->
                    val callback = filePathCallback ?: return@ActivityResultCallback
                    if (result?.resultCode == RESULT_OK) {
                        callback.onReceiveValue(extractUris(result.data))
                    } else {
                        callback.onReceiveValue(null)
                    }
                    filePathCallback = null
                },
            )
    }

    private fun extractUris(intent: Intent?): Array<Uri?>? =
        intent?.data?.let { arrayOf(it) }
            ?: intent?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }

    private fun initDownloadHandler() {
        imageCache = ImageCache(cacheDir = cacheDir, getWebView = { webView })
        downloadHandler =
            DownloadHandler(
                activity = this,
                getWebView = { webView },
                getBaseUrl = { webapp.baseUrl },
                getProgressBar = { progressBar },
                onDownloadComplete = {},
            )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWebView() {
        val settings = effectiveSettings
        window.setBackgroundDrawable(themeBackgroundColor.toDrawable())
        setContentView(R.layout.full_webview)
        setupSystemBarScrims()
        applyWindowFlags(settings)
        bindViews()
        setupPullToRefresh(settings)
        webView?.buildUserAgent()
        if (settings.isShowFullscreen == true) hideSystemBars() else showSystemBars()
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

    private fun setupSystemBarScrims() {
        statusBarScrim = findViewById(R.id.statusBarScrim)
        navigationBarScrim = findViewById(R.id.navigationBarScrim)
        if (effectiveSettings.isDynamicStatusBar == true) {
            applyBarColor(themeBackgroundColor)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.webview_root)
        ) { _, insets ->
            val systemInsets =
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            statusBarScrim?.apply {
                layoutParams.height = systemInsets.top
                requestLayout()
            }
            navigationBarScrim?.apply {
                layoutParams.height = systemInsets.bottom
                requestLayout()
            }
            insets
        }
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
        webviewLaunchOverlay = findViewById<View>(R.id.webviewLaunchOverlay).apply {
            setBackgroundColor(themeBackgroundColor)
        }
        armLaunchOverlay()
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

    private fun bestPeelMatch(url: String, currentUuid: String): WebApp? {
        val scores = DataManager.instance.activeWebsites
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
        val match = bestPeelMatch(url, currentUuid) ?: return
        launchWebApp(match, url)
    }

    private fun openInPeel(url: String) {
        val currentUuid = webappUuid ?: return
        val apps = DataManager.instance.activeWebsites
            .filter { it.uuid != currentUuid }
            .sortedWith(compareByDescending<WebApp> {
                domainAffinity(
                    it.baseUrl,
                    url
                )
            }.thenBy { it.title })
        if (apps.isEmpty()) return
        val adapter = ListPickerAdapter(apps) { webapp, icon, name, detail ->
            name.text = webapp.title
            icon.setImageBitmap(webapp.resolveIcon())
            detail.text = webapp.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
                ?.let { shortLabel(it) } ?: getString(R.string.none)
            detail.visibility = View.VISIBLE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_in_peel)
            .setAdapter(adapter) { _, position -> launchWebApp(apps[position], url) }
            .show()
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

    private fun showBiometricPrompt() {
        if (biometricPromptActive) return
        biometricPromptActive = true

        armLaunchOverlay()

        BiometricPromptHelper(this)
            .showPrompt(
                {
                    setBiometricUnlocked()
                    biometricPromptActive = false
                    onPageFullyLoaded()
                },
                {
                    biometricPromptActive = false
                    finish()
                },
                getString(R.string.bioprompt_restricted_webapp),
            )
    }

    private fun scheduleAutoReload() {
        val handler = reloadHandler ?: return
        val interval = effectiveSettings.timeAutoReload?.coerceAtLeast(1) ?: return
        handler.postDelayed(
            {
                currentlyReloading = true
                webView?.reload()
                scheduleAutoReload()
            },
            interval * 1000L,
        )
    }

    private fun isBiometricUnlocked(): Boolean {
        val uuid = webappUuid ?: return false
        return unlockedBiometricWebapps.contains(uuid)
    }

    private fun setBiometricUnlocked() {
        val uuid = webappUuid ?: return
        unlockedBiometricWebapps.add(uuid)
    }

    private fun clearBiometricUnlock() {
        val uuid = webappUuid ?: return
        unlockedBiometricWebapps.remove(uuid)
    }

    private fun clearBiometricUnlocks() {
        unlockedBiometricWebapps.clear()
    }

    companion object {
        private const val UI_ANIMATION_DURATION_MS = 300L
        private const val OVERLAY_HIDE_FALLBACK_MS = 800L
        private val unlockedBiometricWebapps = mutableSetOf<String>()
    }
}
