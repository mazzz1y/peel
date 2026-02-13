package wtf.mazy.peel.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
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
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.ShareCompat.IntentBuilder
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.DialogHttpAuthBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.ui.BiometricPromptHelper
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.DateUtils.convertStringToCalendar
import wtf.mazy.peel.util.DateUtils.isInInterval
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.NotificationUtils.showInfoSnackBar
import wtf.mazy.peel.util.WebViewLauncher
import wtf.mazy.peel.webview.ChromeClientHost
import wtf.mazy.peel.webview.DownloadHandler
import wtf.mazy.peel.webview.PeelWebChromeClient
import wtf.mazy.peel.webview.PeelWebViewClient
import wtf.mazy.peel.webview.WebViewClientHost
import wtf.mazy.peel.webview.WebViewNotificationManager

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

    private lateinit var webapp: WebApp
    private lateinit var notificationManager: WebViewNotificationManager
    private lateinit var downloadHandler: DownloadHandler

    private var biometricAuthenticated = false
    private var biometricPromptActive = false

    private var geoPermissionRequestCallback: GeolocationPermissions.Callback? = null
    private var geoPermissionRequestOrigin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        DataManager.instance.loadAppData()
        webapp =
            webappUuid?.let { DataManager.instance.getWebApp(it) }
                ?: run {
                    NotificationUtils.showToast(this, getString(R.string.webapp_not_found))
                    finishAndRemoveTask()
                    return
                }

        initNotificationManager()
        initFilePickerLauncher()
        initDownloadHandler()

        if (webapp.isUseContainer && webapp.isEphemeralSandbox) {
            SandboxManager.wipeSandboxStorage(webapp.uuid)
        }

        applyTaskSnapshotProtection()

        if (!setupWebView()) return

        if (webapp.effectiveSettings.isBiometricProtection == true) {
            showBiometricPrompt()
        }

        setupBackNavigation()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
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
        biometricAuthenticated = false
        notificationManager.unregisterReceiver()
        notificationManager.hideNotification()

        if (webapp.effectiveSettings.isAllowMediaPlaybackInBackground != true) {
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
        if (isFinishing && webapp.isUseContainer && webapp.isEphemeralSandbox) {
            webView?.destroy()
            webView = null
            SandboxManager.wipeSandboxStorage(webapp.uuid)
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

        val newUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        if (newUuid != null && newUuid != webappUuid) {
            val newWebapp = DataManager.instance.getWebApp(newUuid) ?: return
            if (Application.getProcessName() != packageName) {
                extractSandboxId()?.let { SandboxManager.saveSandboxUuid(it, newUuid) }
            }
            webappUuid = newUuid
            webapp = newWebapp
            applyTaskSnapshotProtection()
            webView?.loadUrl(newWebapp.baseUrl)
        }
    }

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
        AlertDialog.Builder(this)
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

    override fun finishActivity() = finish()

    override fun showToast(message: String) {
        NotificationUtils.showToast(this, message)
    }

    override fun startExternalIntent(uri: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
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

    // Private methods

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
                            ),
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
    private fun setupWebView(): Boolean {
        if (!resolveSandboxRouting()) return false

        val settings = webapp.effectiveSettings
        setContentView(R.layout.full_webview)
        applyWindowFlags(settings)
        bindViews()
        setupPullToRefresh(settings)
        sanitizeUserAgent()
        applyCustomUserAgent(settings)
        if (settings.isShowFullscreen == true) hideSystemBars() else showSystemBars()
        configureWebViewSettings(settings)
        setDarkModeIfNeeded()
        configureCookies(settings)
        configureZoom(settings)

        customHeaders = buildCustomHeaders(settings)
        loadURL(webapp.baseUrl)

        webView?.webChromeClient = PeelWebChromeClient(this)
        setupLongClickShare(settings)
        webView?.let { downloadHandler.install(it) }

        return true
    }

    private fun resolveSandboxRouting(): Boolean {
        val isInSandboxProcess = Application.getProcessName() != packageName

        if (!isInSandboxProcess && webapp.isUseContainer) {
            redirectToCorrectProcess(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return false
        }

        if (isInSandboxProcess && !webapp.isUseContainer) {
            redirectToCorrectProcess(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return false
        }

        if (isInSandboxProcess) {
            val sandboxId = extractSandboxId()
            val currentSandboxUuid = sandboxId?.let { SandboxManager.getSandboxUuid(it) }

            if (currentSandboxUuid != webapp.uuid) {
                if (sandboxId != null) {
                    SandboxManager.saveSandboxUuid(sandboxId, webapp.uuid)
                } else {
                    redirectToCorrectProcess(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    return false
                }
            }
        }
        return true
    }

    private fun redirectToCorrectProcess(flags: Int) {
        val intent = WebViewLauncher.createWebViewIntent(webapp, this)
        intent?.addFlags(flags)
        startActivity(intent)
        finishAndRemoveTask()
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

    private fun sanitizeUserAgent() {
        val fieldName =
            WebViewActivity::class
                .java
                .declaredFields
                .firstOrNull { it.type == WebView::class.java }
                ?.name ?: return
        val uaString = webView?.settings?.userAgentString?.replace("; $fieldName", "") ?: ""
        webView?.settings?.userAgentString = uaString
    }

    private fun applyCustomUserAgent(settings: WebAppSettings) {
        settings.customHeaders
            ?.get("User-Agent")
            ?.takeIf { it.isNotEmpty() }
            ?.let { ua ->
                webView?.settings?.userAgentString =
                    ua.replace("\u0000", "").replace("\n", "").replace("\r", "")
            }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings(settings: WebAppSettings) {
        webView?.apply {
            webViewClient = PeelWebViewClient(this@WebViewActivity)
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
                    (linkUrl.startsWith("http://") || linkUrl.startsWith("https://"))) {
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
            })
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
        val extraHeaders =
            mutableMapOf(
                "DNT" to "1",
                "X-REQUESTED-WITH" to "",
            )
        settings.customHeaders?.forEach { (key, value) -> extraHeaders[key] = value }
        return extraHeaders.toMap()
    }

    private fun applyTaskSnapshotProtection() {
        val shouldProtect = webapp.effectiveSettings.isBiometricProtection == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!shouldProtect)
        }

        val icon =
            if (webapp.hasCustomIcon) {
                android.graphics.BitmapFactory.decodeFile(webapp.iconFile.absolutePath)
            } else {
                LetterIconGenerator.generate(
                    webapp.title,
                    webapp.baseUrl,
                    (48 * resources.displayMetrics.density).toInt(),
                )
            }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(webapp.title, icon))
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
            interval * 1000L)
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

    private fun extractSandboxId(): Int? {
        val processName = Application.getProcessName()
        return Regex(":sandbox_(\\d+)$").find(processName)?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
