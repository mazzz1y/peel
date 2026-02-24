package wtf.mazy.peel.activities

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.ShareCompat.IntentBuilder
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.DialogHttpAuthBinding
import wtf.mazy.peel.media.MediaJsBridge
import wtf.mazy.peel.media.MediaPlaybackManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.BiometricPromptHelper
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.DateUtils.convertStringToCalendar
import wtf.mazy.peel.util.DateUtils.isInInterval
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.NotificationUtils.showInfoSnackBar
import wtf.mazy.peel.util.WebViewLauncher
import wtf.mazy.peel.util.buildUserAgent
import wtf.mazy.peel.webview.ChromeClientHost
import wtf.mazy.peel.webview.DownloadHandler
import wtf.mazy.peel.webview.PeelWebChromeClient
import wtf.mazy.peel.webview.PeelWebViewClient
import wtf.mazy.peel.webview.PermissionResult
import wtf.mazy.peel.webview.WebViewClientHost
import wtf.mazy.peel.webview.WebViewNotificationManager
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
    private var filePickerLauncher: ActivityResultLauncher<Intent?>? = null
    private var reloadHandler: Handler? = null
    override var urlOnFirstPageload = ""

    private val webapp: WebApp
        get() = DataManager.instance.getWebApp(webappUuid!!)!!

    private lateinit var notificationManager: WebViewNotificationManager
    private lateinit var downloadHandler: DownloadHandler
    private lateinit var peelWebViewClient: PeelWebViewClient

    private var biometricAuthenticated = false
    private var biometricPromptActive = false

    private var statusBarScrim: View? = null
    private var navigationBarScrim: View? = null
    private var currentBarColor: Int? = null
    private var barColorAnimator: ValueAnimator? = null
    private var geoPermissionRequestCallback: GeolocationPermissions.Callback? = null
    private var geoPermissionRequestOrigin: String? = null
    private var mediaPlaybackManager: MediaPlaybackManager? = null

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

        initNotificationManager()
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

        applyTaskSnapshotProtection()
        setupWebView()

        if (webapp.effectiveSettings.isBiometricProtection == true) {
            showBiometricPrompt()
        }

        setupBackNavigation()
    }

    override fun onResume() {
        super.onResume()
        if (SandboxManager.currentSlotId != null) DataManager.instance.loadAppData()
        webView?.onResume()
        webView?.resumeTimers()
        mediaPlaybackManager?.setBackground(false)
        if (webView != null) setDarkModeIfNeeded()

        notificationManager.registerReceiver()
        showNotification()

        if (webapp.effectiveSettings.isBiometricProtection == true && !biometricAuthenticated) {
            showBiometricPrompt()
        }

        if (webapp.effectiveSettings.isAutoReload == true) {
            reloadHandler = Handler(Looper.getMainLooper())
            scheduleAutoReload()
        }
    }

    override fun onPause() {
        super.onPause()
        val bgMedia = webapp.effectiveSettings.isAllowMediaPlaybackInBackground == true
        biometricAuthenticated = false
        notificationManager.unregisterReceiver()
        notificationManager.hideNotification()

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

        if (webapp.effectiveSettings.isClearCache == true) webView?.clearCache(true)

        reloadHandler?.removeCallbacksAndMessages(null)
        reloadHandler = null
    }

    override fun onDestroy() {
        if (::peelWebViewClient.isInitialized) {
            peelWebViewClient.clearDynamicBarColorRetry()
        }
        barColorAnimator?.cancel()
        barColorAnimator = null
        mediaPlaybackManager?.release()
        mediaPlaybackManager = null
        val app = webappUuid?.let { DataManager.instance.getWebApp(it) }
        if (isFinishing && app != null) {
            val sandboxId = WebViewLauncher.resolveSandboxId(app)
            if (sandboxId != null) {
                webView?.destroy()
                webView = null
                if (WebViewLauncher.isEphemeralSandbox(app)) {
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
        applyTaskSnapshotProtection()
        loadURL(sharedUrlFromIntent() ?: webapp.baseUrl)
    }

    override val webAppName: String
        get() = webapp.title

    override val effectiveSettings: WebAppSettings
        get() = webapp.effectiveSettings

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

    override fun showNotification() {
        if (!notificationManager.showNotification()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION,
                )
            }
        }
    }

    override fun showHttpAuthDialog(handler: HttpAuthHandler, host: String?, realm: String?) {
        val localBinding = DialogHttpAuthBinding.inflate(LayoutInflater.from(this))
        MaterialAlertDialogBuilder(this)
            .setView(localBinding.root)
            .setTitle(getString(R.string.http_auth_title))
            .setMessage(getString(R.string.enter_http_auth_credentials, realm, host))
            .setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                handler.proceed(
                    localBinding.username.text.toString(),
                    localBinding.password.text.toString(),
                )
            }
            .setNegativeButton(getString(R.string.cancel)) { _: DialogInterface?, _: Int ->
                handler.cancel()
            }
            .show()
    }

    override val isForceDarkActive: Boolean
        get() = getDelegate().localNightMode == AppCompatDelegate.MODE_NIGHT_YES

    @SuppressLint("RequiresFeature")
    override fun setDarkModeIfNeeded() {
        val settings = webapp.effectiveSettings
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
        if (url.startsWith("http://") && webapp.effectiveSettings.isAlwaysHttps == true) {
            finalUrl = url.replaceFirst("http://", "https://")
        }
        webView?.loadUrl(finalUrl, customHeaders ?: emptyMap())
    }

    private fun sharedUrlFromIntent(): String? =
        intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()

    override fun finishActivity() = finish()

    override fun showToast(message: String) {
        NotificationUtils.showToast(this, message)
    }

    override fun updateStatusBarColor(color: Int) {
        val fromColor = currentBarColor ?: themeBackgroundColor
        if (fromColor == color) {
            applyBarColor(color)
            return
        }
        barColorAnimator?.cancel()
        barColorAnimator =
            ValueAnimator.ofArgb(fromColor, color).apply {
                duration = 300
                addUpdateListener { applyBarColor(it.animatedValue as Int) }
                start()
            }
    }

    override fun onPageStarted() {
        mediaPlaybackManager?.injectPolyfill()
    }

    override fun onPageFullyLoaded() {
        webView?.let { peelWebViewClient.extractDynamicBarColor(it) }
        mediaPlaybackManager?.injectObserver()
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
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    override val themeBackgroundColor: Int
        get() {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            return tv.data
        }

    override fun runOnUi(action: Runnable) = runOnUiThread(action)

    override fun launchFilePicker(intent: Intent?): Boolean {
        filePickerLauncher?.launch(intent) ?: return false
        return true
    }

    override fun showSnackBar(message: String) {
        showInfoSnackBar(this, message, Snackbar.LENGTH_LONG)
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
        if (webapp.effectiveSettings.isShowFullscreen == true) return
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

    override fun requestHostPermissions(permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(this, permissions, requestCode)
    }

    override fun hasPermissions(vararg permissions: String): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onGeoPermissionResult(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
        allow: Boolean,
    ) {
        if (allow) {
            callback?.invoke(origin, true, false)
            return
        }
        geoPermissionRequestCallback = callback
        geoPermissionRequestOrigin = origin
    }

    override fun showPermissionDialog(message: String, onResult: (PermissionResult) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_permission, null)
        view.findViewById<android.widget.TextView>(R.id.textTitle).text = message
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setView(view)
                .setOnCancelListener { onResult(PermissionResult.DENY_ONCE) }
                .show()

        fun bind(id: Int, result: PermissionResult) =
            view.findViewById<View>(id).setOnClickListener {
                dialog.dismiss()
                onResult(result)
            }
        bind(R.id.btnAllowSession, PermissionResult.ALLOW_SESSION)
        bind(R.id.btnAllowOnce, PermissionResult.ALLOW_ONCE)
        bind(R.id.btnDenyOnce, PermissionResult.DENY_ONCE)
        bind(R.id.btnDenySession, PermissionResult.DENY_SESSION)
    }

    private fun initNotificationManager() {
        notificationManager =
            WebViewNotificationManager(
                activity = this,
                getWebapp = { webapp },
                getWebappUuid = { webapp.uuid },
                getWebView = { webView },
                onReload = { webView?.reload() },
                onHome = { loadURL(webapp.baseUrl) },
            )
        notificationManager.createChannel()
    }

    private fun initFilePickerLauncher() {
        filePickerLauncher =
            registerForActivityResult(
                StartActivityForResult(),
                ActivityResultCallback { result ->
                    val callback = filePathCallback ?: return@ActivityResultCallback
                    if (result?.resultCode == RESULT_CANCELED) {
                        callback.onReceiveValue(null)
                    } else if (result?.resultCode == RESULT_OK) {
                        callback.onReceiveValue(
                            android.webkit.WebChromeClient.FileChooserParams.parseResult(
                                result.resultCode,
                                result.data,
                            )
                        )
                    }
                    filePathCallback = null
                },
            )
    }

    private fun initDownloadHandler() {
        downloadHandler =
            DownloadHandler(
                activity = this,
                getWebView = { webView },
                getBaseUrl = { webapp.baseUrl },
                getProgressBar = { progressBar },
                onDownloadComplete = { showNotification() },
            )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWebView() {
        val settings = webapp.effectiveSettings
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
        loadURL(sharedUrlFromIntent() ?: webapp.baseUrl)

        webView?.webChromeClient = PeelWebChromeClient(this)
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
        if (webapp.effectiveSettings.isDynamicStatusBar == true) {
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
        webView = findViewById(R.id.webview)
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
                allowFileAccess = true
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
        webView?.setOnLongClickListener { _ ->
            if (settings.isLongClickShare != true) return@setOnLongClickListener false
            val result = webView?.hitTestResult
            if (result?.type == HitTestResult.SRC_ANCHOR_TYPE) {
                val linkUrl = result.extra
                if (!linkUrl.isNullOrEmpty() &&
                    (linkUrl.startsWith("http://") || linkUrl.startsWith("https://"))
                ) {
                    IntentBuilder(this@WebViewActivity)
                        .setType("text/plain")
                        .setChooserTitle("Share Link")
                        .setText(linkUrl)
                        .startChooser()
                    return@setOnLongClickListener true
                }
            }
            false
        }
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

        val currentHost = wv.url?.toUri()?.host
        val baseHost = webapp.baseUrl.toUri().host ?: return 1
        val prevHost = history.getItemAtIndex(currentIndex - 1)?.url?.toUri()?.host

        return if (currentHost == baseHost && prevHost != baseHost) 0 else 1
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
        val shouldProtect = webapp.effectiveSettings.isBiometricProtection == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!shouldProtect)
        }

        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(webapp.title, webapp.resolveIcon()))
    }

    private fun showBiometricPrompt() {
        if (biometricPromptActive) return
        biometricPromptActive = true
        val fullActivityView = findViewById<View>(R.id.webviewActivity)
        fullActivityView.visibility = View.GONE
        BiometricPromptHelper(this)
            .showPrompt(
                {
                    biometricAuthenticated = true
                    biometricPromptActive = false
                    fullActivityView.visibility = View.VISIBLE
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
        val interval = webapp.effectiveSettings.timeAutoReload ?: 0
        handler.postDelayed(
            {
                currentlyReloading = true
                webView?.reload()
                scheduleAutoReload()
            },
            interval * 1000L,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showNotification()
            }
            return
        }

        val granted =
            grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        when (requestCode) {
            Const.PERMISSION_RC_LOCATION -> {
                geoPermissionRequestCallback?.invoke(geoPermissionRequestOrigin, granted, false)
                geoPermissionRequestCallback = null
                geoPermissionRequestOrigin = null
            }

            Const.PERMISSION_CAMERA,
            Const.PERMISSION_AUDIO -> if (granted) webView?.reload()

            Const.PERMISSION_RC_STORAGE -> if (granted) downloadHandler.onStoragePermissionGranted()
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
