package wtf.mazy.peel.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ShareCompat.IntentBuilder
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.DialogHttpAuthBinding
import wtf.mazy.peel.util.BiometricPromptHelper
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.DateUtils.convertStringToCalendar
import wtf.mazy.peel.util.DateUtils.isInInterval
import wtf.mazy.peel.util.EntryPointUtils.entryPointReached
import wtf.mazy.peel.util.LocaleUtils.fileEnding
import wtf.mazy.peel.util.NotificationUtils.showInfoSnackBar
import wtf.mazy.peel.util.Utility.getFileNameFromDownload
import wtf.mazy.peel.util.WebViewLauncher
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import java.util.Collections
import java.util.stream.Stream

open class WebViewActivity : AppCompatActivity() {
    var webappUuid: String? = null
    var webView: WebView? = null
        private set

    private var progressBar: ProgressBar? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var currentlyReloading = true
    private var mGeoPermissionRequestCallback: GeolocationPermissions.Callback? = null
    private var mGeoPermissionRequestOrigin: String? = null
    private var dlRequest: DownloadManager.Request? = null
    private var customHeaders: MutableMap<String?, String?>? = null
    private var filePathCallback: ValueCallback<Array<Uri?>?>? = null
    private var filePickerLauncher: ActivityResultLauncher<Intent?>? = null

    private var reloadHandler: Handler? = null
    private lateinit var webapp: WebApp
    private var urlOnFirstPageload = ""
    private var biometricAuthenticated = false
    private var biometricPromptActive = false
    private val notificationReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val action = intent.action
                Log.d("Notification", "Received action: $action")
                if (ACTION_RELOAD == action) {
                    reloadPage()
                } else if (ACTION_HOME == action) {
                    goToHome()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        filePickerLauncher =
            registerForActivityResult(
                StartActivityForResult(),
                ActivityResultCallback { result: ActivityResult? ->
                    if (filePathCallback == null) return@ActivityResultCallback
                    if (result!!.resultCode == RESULT_CANCELED) {
                        filePathCallback!!.onReceiveValue(null)
                    } else if (result.resultCode == RESULT_OK) {
                        filePathCallback!!.onReceiveValue(
                            FileChooserParams.parseResult(result.resultCode, result.data))
                    }
                    filePathCallback = null
                },
            )

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        entryPointReached()
        webapp = webappUuid?.let { DataManager.instance.getWebApp(it) }!!
        setupWebView()

        if (webapp.effectiveSettings.isBiometricProtection == true) {
            showBiometricPrompt()
        }

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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWebView() {
        val settings = webapp.effectiveSettings

        val isInSandboxProcess = Application.getProcessName() != packageName

        if (!isInSandboxProcess && webapp.isUseContainer) {
            val intent = WebViewLauncher.createWebViewIntent(webapp, this)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finishAndRemoveTask()
            return
        }

        if (isInSandboxProcess && !webapp.isUseContainer) {
            val intent = WebViewLauncher.createWebViewIntent(webapp, this)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finishAndRemoveTask()
            return
        }

        if (isInSandboxProcess) {
            val sandboxId = extractSandboxId()
            val currentSandboxUuid = sandboxId?.let { SandboxManager.getSandboxUuid(it) }

            if (currentSandboxUuid != webapp.uuid) {
                if (sandboxId != null) {
                    SandboxManager.saveSandboxUuid(sandboxId, webapp.uuid)
                } else {
                    val intent = WebViewLauncher.createWebViewIntent(webapp, this)
                    intent?.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finishAndRemoveTask()
                    return
                }
            }
        }

        setContentView(R.layout.full_webview)

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

        val url = webapp.baseUrl

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

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

        val fieldName =
            Stream.of(*WebViewActivity::class.java.declaredFields)
                .filter { f -> f.type == WebView::class.java }
                .findFirst()
                .orElseThrow<RuntimeException>(null)
                .name
        val uaString = webView?.settings?.userAgentString?.replace("; $fieldName", "") ?: ""
        webView?.settings?.userAgentString = uaString

        settings.customHeaders
            ?.get("User-Agent")
            ?.takeIf { it.isNotEmpty() }
            ?.let { ua ->
                webView?.settings?.userAgentString =
                    ua.replace("\u0000", "").replace("\n", "").replace("\r", "")
            }

        if (settings.isShowFullscreen == true) {
            hideSystemBars()
        } else {
            showSystemBars()
        }
        webView?.apply {
            webViewClient = CustomBrowser()
            this.settings.apply {
                safeBrowsingEnabled = false
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
        setDarkModeIfNeeded()

        CookieManager.getInstance().apply {
            setAcceptCookie(settings.isAllowCookies == true)
            setAcceptThirdPartyCookies(webView, settings.isAllowThirdPartyCookies == true)
        }

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

        customHeaders = initCustomHeaders(settings)
        loadURL(webView, url)
        webView?.webChromeClient = CustomWebChromeClient()
        webView?.setOnLongClickListener { _ ->
            if (settings.isLongClickShare != true) {
                return@setOnLongClickListener false
            }
            val result = webView?.hitTestResult
            val type = result?.type
            if (type == HitTestResult.SRC_ANCHOR_TYPE) {
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

        webView?.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            if (!dlUrl.isNullOrEmpty()) {
                if (mimeType == "application/pdf" && contentDisposition.isNullOrEmpty()) {
                    return@setDownloadListener
                }
                if (dlUrl.startsWith("blob:")) {
                    return@setDownloadListener
                }
                val request: DownloadManager.Request?
                try {
                    request = DownloadManager.Request(dlUrl.toUri())
                } catch (_: Exception) {
                    showInfoSnackBar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT)
                    return@setDownloadListener
                }
                val fileName = getFileNameFromDownload(dlUrl, contentDisposition, mimeType)

                request.setMimeType(mimeType)
                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(dlUrl))
                request.addRequestHeader("User-Agent", userAgent)
                request.setTitle(fileName)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager?

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val perms =
                        arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                        )
                    if (!hasPermissions(*perms)) {
                        dlRequest = request
                        ActivityCompat.requestPermissions(
                            this@WebViewActivity,
                            perms,
                            Const.PERMISSION_RC_STORAGE,
                        )
                    } else {
                        dm?.let {
                            it.enqueue(request)
                            showInfoSnackBar(
                                this, getString(R.string.file_download), Snackbar.LENGTH_SHORT)
                        }
                    }
                } else {
                    dm?.let {
                        it.enqueue(request)
                        showInfoSnackBar(
                            this, getString(R.string.file_download), Snackbar.LENGTH_SHORT)
                    }
                }

                val currentUrl = webView?.url
                if (currentUrl == null || currentUrl == dlUrl) {
                    if (webView?.canGoBack() == true) {
                        webView?.goBack()
                    } else {
                        webView?.loadUrl(webapp.baseUrl)
                    }
                }
                progressBar?.visibility = ProgressBar.GONE
                Handler(Looper.getMainLooper()).postDelayed({ showNotification() }, 300)
            }
        }
    }

    @SuppressLint("RequiresFeature")
    private fun setDarkModeIfNeeded() {
        val settings = webapp.effectiveSettings
        val needsForcedDarkMode =
            (settings.isUseTimespanDarkMode == true &&
                isInInterval(
                    convertStringToCalendar(settings.timespanDarkModeBegin)!!,
                    Calendar.getInstance(),
                    convertStringToCalendar(settings.timespanDarkModeEnd)!!,
                )) || (settings.isUseTimespanDarkMode != true && settings.isForceDarkMode == true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isAlgorithmicDarkeningSupported =
                WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)

            if (needsForcedDarkMode) {
                webView!!.setBackgroundColor(Color.BLACK)
                webView!!.isForceDarkAllowed = true
                getDelegate().localNightMode = AppCompatDelegate.MODE_NIGHT_YES
                if (isAlgorithmicDarkeningSupported) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView!!.settings, true)
                }
            } else {
                getDelegate().localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                if (isAlgorithmicDarkeningSupported) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView!!.settings, false)
                }
            }
        }
    }

    @SuppressLint("NonConstantResourceId")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        this.setDarkModeIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        if (newUuid != null && newUuid != webappUuid) {
            val newWebapp = DataManager.instance.getWebApp(newUuid)
            if (newWebapp != null) {
                val isInSandboxProcess = Application.getProcessName() != packageName
                if (isInSandboxProcess) {
                    val sandboxId = extractSandboxId()
                    if (sandboxId != null) {
                        SandboxManager.saveSandboxUuid(sandboxId, newUuid)
                    }
                }
                webappUuid = newUuid
                webapp = newWebapp
                webView?.loadUrl(newWebapp.baseUrl)
            }
        }
    }

    private fun showBiometricPrompt() {
        if (biometricPromptActive) return
        biometricPromptActive = true
        val fullActivityView = findViewById<View>(R.id.webviewActivity)
        fullActivityView.visibility = View.GONE
        BiometricPromptHelper(this@WebViewActivity)
            .showPrompt(
                {
                    biometricAuthenticated = true
                    biometricPromptActive = false
                    fullActivityView.visibility = View.VISIBLE
                },
                {
                    biometricPromptActive = false
                    this.finish()
                },
                getString(R.string.bioprompt_restricted_webapp),
            )
    }

    override fun onResume() {
        super.onResume()

        webView?.onResume()
        webView?.resumeTimers()
        if (webView != null) setDarkModeIfNeeded()

        val filter = IntentFilter()
        filter.addAction(ACTION_RELOAD)
        filter.addAction(ACTION_HOME)
        ContextCompat.registerReceiver(
            this,
            notificationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        showNotification()

        if (webapp.effectiveSettings.isBiometricProtection == true && !biometricAuthenticated) {
            showBiometricPrompt()
        }

        if (webapp.effectiveSettings.isAutoReload == true) {
            reloadHandler = Handler(Looper.getMainLooper())
            reload()
        }
    }

    override fun onPause() {
        super.onPause()
        biometricAuthenticated = false

        try {
            unregisterReceiver(notificationReceiver)
        } catch (_: IllegalArgumentException) {}
        hideNotification()

        if (webapp.effectiveSettings.isAllowMediaPlaybackInBackground != true) {
            webView?.evaluateJavascript(
                "document.querySelectorAll('audio').forEach(x => x.pause());document.querySelectorAll('video').forEach(x => x.pause());",
                null,
            )
            webView?.onPause()
            webView?.pauseTimers()
        }

        if (webapp.effectiveSettings.isClearCache == true) webView?.clearCache(true)

        if (reloadHandler != null) {
            reloadHandler!!.removeCallbacksAndMessages(null)
            Log.d("CLEANUP", "Stopped reload handler")
        }
    }

    private fun reload() {
        val timeAutoreload = webapp.effectiveSettings.timeAutoReload ?: 0
        reloadHandler!!.postDelayed(
            {
                currentlyReloading = true
                webView!!.reload()
                reload()
            },
            timeAutoreload * 1000L,
        )
    }

    private fun initCustomHeaders(settings: WebAppSettings): MutableMap<String?, String?> {
        val extraHeaders: MutableMap<String?, String?> = HashMap()
        extraHeaders["DNT"] = "1"
        extraHeaders["X-REQUESTED-WITH"] = ""
        settings.customHeaders?.forEach { (key, value) -> extraHeaders[key] = value }
        return Collections.unmodifiableMap<String?, String?>(extraHeaders)
    }

    private fun loadURL(view: WebView?, url: String) {
        webappUuid?.let { DataManager.instance.getWebApp(it) }
        var finalUrl = url

        if (url.startsWith("http://") && webapp.effectiveSettings.isAlwaysHttps == true) {
            finalUrl = url.replaceFirst("http://", "https://")
        }

        view?.loadUrl(finalUrl, customHeaders!!)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    private fun showSystemBars() {
        if (webapp.effectiveSettings.isShowFullscreen == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(true)
            val controller = window.insetsController
            if (controller != null) {
                controller.show(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("Notification", "Permission granted, showing notification")
                showNotification()
            } else {
                Log.w("Notification", "Permission denied")
            }
            return
        }

        val granted =
            grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        when (requestCode) {
            Const.PERMISSION_RC_LOCATION -> handleGeoPermissionCallback(granted)
            Const.PERMISSION_CAMERA,
            Const.PERMISSION_AUDIO -> if (granted) webView?.reload()

            Const.PERMISSION_RC_STORAGE -> {
                if (granted && dlRequest != null) {
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager?
                    dm?.let {
                        it.enqueue(dlRequest)
                        showInfoSnackBar(
                            this, getString(R.string.file_download), Snackbar.LENGTH_SHORT)
                    }
                    dlRequest = null
                }
            }
        }
    }

    private fun handleGeoPermissionCallback(allow: Boolean) {
        if (mGeoPermissionRequestCallback != null) {
            mGeoPermissionRequestCallback!!.invoke(mGeoPermissionRequestOrigin, allow, false)
            mGeoPermissionRequestCallback = null
        }
    }

    private fun hasPermissions(vararg permissions: String): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private inner class CustomWebChromeClient : WebChromeClient() {
        private var mCustomView: View? = null
        private var mCustomViewCallback: CustomViewCallback? = null
        private var mOriginalOrientation = 0
        private var mOriginalSystemUiVisibility = 0

        fun handlePermissionRequest(
            isEnabled: Boolean,
            androidPermissions: Array<String>,
            requestCode: Int,
            permissionsToGrant: MutableList<String?>,
            webkitPermission: Array<String?>,
        ) {
            if (!isEnabled) {
                handleGeoPermissionCallback(false)
                return
            }

            if (!hasPermissions(*androidPermissions)) {
                ActivityCompat.requestPermissions(
                    this@WebViewActivity, androidPermissions, requestCode)
            } else {
                permissionsToGrant.addAll(listOf(*webkitPermission))
                handleGeoPermissionCallback(true)
            }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            pFilePathCallback: ValueCallback<Array<Uri?>?>?,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            filePathCallback = pFilePathCallback
            try {
                val intent = fileChooserParams.createIntent()
                filePickerLauncher!!.launch(intent)
            } catch (e: Exception) {
                showInfoSnackBar(
                    this@WebViewActivity,
                    getString(R.string.no_filemanager),
                    Snackbar.LENGTH_LONG,
                )
                Log.e("WebViewActivity", "Failed to launch file picker", e)
            }
            return true
        }

        override fun getDefaultVideoPoster(): Bitmap {
            val bitmap = createBitmap(1, 1)
            val canvas = Canvas(bitmap)
            canvas.drawARGB(0, 0, 0, 0)
            return bitmap
        }

        override fun onHideCustomView() {
            (window.decorView as FrameLayout).removeView(this.mCustomView)
            this.mCustomView = null
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = this.mOriginalSystemUiVisibility
            requestedOrientation = this.mOriginalOrientation
            this.mCustomViewCallback!!.onCustomViewHidden()
            this.mCustomViewCallback = null
            showSystemBars()
        }

        override fun onShowCustomView(pView: View?, pViewCallback: CustomViewCallback?) {
            if (this.mCustomView != null) {
                onHideCustomView()
                return
            }
            this.mCustomView = pView
            @Suppress("DEPRECATION")
            this.mOriginalSystemUiVisibility = window.decorView.systemUiVisibility
            this.mOriginalOrientation = requestedOrientation
            this.mCustomViewCallback = pViewCallback
            (window.decorView as FrameLayout).addView(
                this.mCustomView, FrameLayout.LayoutParams(-1, -1))
            hideSystemBars()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val permissionsToGrant: MutableList<String?> = ArrayList()

            val containsDrmRequest =
                listOf(*request.resources).contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            val containsCameraRequest =
                listOf(*request.resources).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            val containsMicrophoneRequest =
                listOf(*request.resources).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

            if (containsDrmRequest) {
                this.handlePermissionRequest(
                    webapp.effectiveSettings.isDrmAllowed == true,
                    arrayOf(),
                    -1,
                    permissionsToGrant,
                    arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID),
                )
            }
            if (containsCameraRequest) {
                this.handlePermissionRequest(
                    webapp.effectiveSettings.isCameraPermission == true,
                    arrayOf(Manifest.permission.CAMERA),
                    Const.PERMISSION_CAMERA,
                    permissionsToGrant,
                    arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
                )
            }
            if (containsMicrophoneRequest) {
                this.handlePermissionRequest(
                    webapp.effectiveSettings.isMicrophonePermission == true,
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS),
                    Const.PERMISSION_AUDIO,
                    permissionsToGrant,
                    arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                )
            }

            request.grant(permissionsToGrant.toTypedArray())
        }

        override fun onProgressChanged(view: WebView?, progress: Int) {
            if (webapp.effectiveSettings.isShowProgressbar == true || currentlyReloading) {
                if (progressBar!!.isGone && progress < 100) {
                    progressBar!!.visibility = ProgressBar.VISIBLE
                }

                progressBar!!.progress = progress

                if (progress == 100) {
                    progressBar!!.visibility = ProgressBar.GONE
                    currentlyReloading = false
                }
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            mGeoPermissionRequestCallback = callback
            mGeoPermissionRequestOrigin = origin
            this.handlePermissionRequest(
                webapp.effectiveSettings.isAllowLocationAccess == true,
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                Const.PERMISSION_RC_LOCATION,
                mutableListOf(),
                arrayOf(),
            )
        }
    }

    private fun showHttpAuthDialog(handler: HttpAuthHandler, host: String?, realm: String?) {
        val localBinding = DialogHttpAuthBinding.inflate(LayoutInflater.from(this))
        AlertDialog.Builder(this)
            .setView(localBinding.getRoot())
            .setTitle(getString(R.string.http_auth_title))
            .setMessage(getString(R.string.enter_http_auth_credentials, realm, host))
            .setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                val username = localBinding.username.getText().toString()
                val password = localBinding.password.getText().toString()
                handler.proceed(username, password)
            }
            .setNegativeButton(getString(R.string.cancel)) { _: DialogInterface?, _: Int ->
                handler.cancel()
            }
            .show()
    }

    private inner class CustomBrowser : WebViewClient() {
        override fun onReceivedHttpAuthRequest(
            view: WebView?,
            handler: HttpAuthHandler,
            host: String?,
            realm: String?,
        ) {
            showHttpAuthDialog(handler, host, realm)
        }

        override fun onPageFinished(view: WebView?, url: String) {
            if (url == "about:blank") {
                val langExtension = fileEnding
                webView!!.loadUrl("file:///android_asset/errorSite/error_$langExtension.html")
            }
            webView!!.evaluateJavascript(
                "document.addEventListener(\"visibilitychange\",function (event) {event.stopImmediatePropagation();},true);",
                null,
            )
            showNotification()
            super.onPageFinished(view, url)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (urlOnFirstPageload.isEmpty()) urlOnFirstPageload = request.url.toString()

            if (webapp.effectiveSettings.isBlockThirdPartyRequests == true) {
                val uri = request.url
                val webappUri = webapp.baseUrl.toUri()
                val webappHost = webappUri.host
                val requestHost = uri.host

                if (requestHost != null &&
                    webappHost != null &&
                    !requestHost.endsWith(webappHost)) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
            if (webapp.effectiveSettings.isIgnoreSslErrors == true) {
                handler.proceed()
                return
            }

            val builder = AlertDialog.Builder(this@WebViewActivity)

            var message = getString(R.string.ssl_error_msg_line1) + " "
            when (error.getPrimaryError()) {
                SslError.SSL_UNTRUSTED ->
                    message += getString(R.string.ssl_error_unknown_authority) + "\n"
                SslError.SSL_EXPIRED -> message += getString(R.string.ssl_error_expired) + "\n"
                SslError.SSL_IDMISMATCH ->
                    message += getString(R.string.ssl_error_id_mismatch) + "\n"
                SslError.SSL_NOTYETVALID ->
                    message += getString(R.string.ssl_error_notyetvalid) + "\n"
            }
            message += getString(R.string.ssl_error_msg_line2) + "\n"

            builder.setTitle(getString(R.string.ssl_error_title))
            builder.setMessage(message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setPositiveButton(getString(R.string.cancel)) { _: DialogInterface?, _: Int ->
                handler.cancel()
            }
            builder.setNegativeButton(getString(R.string.load_anyway)) { _: DialogInterface?, _: Int
                ->
                handler.proceed()
            }
            val dialog = builder.create()
            dialog.show()
            dialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(
                    ContextCompat.getColor(this@WebViewActivity, android.R.color.holo_red_dark))
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(
                    ContextCompat.getColor(this@WebViewActivity, android.R.color.holo_green_dark))
        }

        override fun onLoadResource(view: WebView, url: String?) {
            super.onLoadResource(view, url)

            if (webappUuid
                ?.let { DataManager.instance.getWebApp(it) }
                ?.settings
                ?.isRequestDesktop == true)
                view.evaluateJavascript(
                    """
                         var needsForcedWidth = document.documentElement.clientWidth < 1200;
                         if(needsForcedWidth) {
                           document.querySelector('meta[name="viewport"]').setAttribute('content', 'width=1200px, initial-scale=' + (document.documentElement.clientWidth / 1200));
                         }

                        """
                        .trimIndent(),
                    null,
                )
            view.evaluateJavascript(
                "document.addEventListener(    \"visibilitychange\"    , (event) => {         event.stopImmediatePropagation();    }  );",
                null,
            )
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            runOnUiThread { this@WebViewActivity.setDarkModeIfNeeded() }
            val url = request.url.toString()
            val webapp = webappUuid?.let { DataManager.instance.getWebApp(it) }

            if (!url.startsWith("http://") &&
                !url.startsWith("https://") &&
                !url.startsWith("file://") &&
                !url.startsWith("about:")) {
                if (request.isForMainFrame && request.hasGesture()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        startActivity(intent)
                        return true
                    } catch (_: Exception) {
                        return true
                    }
                }
                return true
            }

            if (webapp?.settings?.isOpenUrlExternal == true) {
                val baseUrl = webapp.baseUrl
                val uri = baseUrl.toUri()
                val host = uri.host
                if (host != null && !url.contains(host)) {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    return true
                }
            }
            loadURL(view, url)
            return true
        }
    }

    private fun createNotificationChannel() {
        val name: CharSequence = "WebView Controls"
        val description = "Controls for WebView navigation"
        val importance = NotificationManager.IMPORTANCE_MIN
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        channel.description = description
        channel.setShowBadge(false)
        channel.setSound(null, null)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        Log.d("Notification", "Notification channel created")
    }

    private fun showNotification() {
        Log.d("Notification", "showNotification called")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                Log.w("Notification", "POST_NOTIFICATIONS permission not granted, requesting...")
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION,
                )
                return
            }
        }

        val currentUrl = webView?.url ?: "Loading..."
        val shareChooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, currentUrl)
            },
            null,
        )
        val shareIntent =
            PendingIntent.getActivity(
                this,
                0,
                shareChooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val reloadIntentAction = Intent(ACTION_RELOAD)
        reloadIntentAction.setPackage(packageName)
        val reloadIntent =
            PendingIntent.getBroadcast(
                this,
                1,
                reloadIntentAction,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val homeIntentAction = Intent(ACTION_HOME)
        homeIntentAction.setPackage(packageName)
        val homeIntent =
            PendingIntent.getBroadcast(
                this,
                2,
                homeIntentAction,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val webappIcon = if (webapp.hasCustomIcon && webapp.customIconPath != null) {
            val iconFile = java.io.File(webapp.customIconPath!!)
            if (iconFile.exists()) android.graphics.BitmapFactory.decodeFile(iconFile.absolutePath) else null
        } else null
        val notificationIcon = webappIcon ?: wtf.mazy.peel.util.LetterIconGenerator.generate(webapp.title, webapp.baseUrl, (48 * resources.displayMetrics.density).toInt())

        val builder: NotificationCompat.Builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(notificationIcon)
                .setContentTitle(webapp.title)
                .setContentText(webView?.url ?: "Loading...")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .addAction(R.drawable.ic_baseline_home_24, "Home", homeIntent)
                .addAction(R.drawable.ic_baseline_content_copy_24, "Share", shareIntent)
                .addAction(R.drawable.ic_baseline_refresh_24, "Reload", reloadIntent)

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
        Log.d("Notification", "Notification shown")
    }

    private fun hideNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun reloadPage() {
        webView!!.reload()
    }

    private fun goToHome() {
        Log.d("Notification", "goToHome called")
        val baseUrl = webapp.baseUrl
        Log.d("Notification", "Loading baseUrl: $baseUrl")
        webView!!.loadUrl(baseUrl)
    }

    // Smart back navigation to skip SSO/external pages
    // Example: github.com/A -> github.com/login -> sso.com -> github.com/dashboard
    // Pressing back on github.com/dashboard skips external + login pages, exits app
    // (because github.com/A triggered the login flow)
    private fun getBackSteps(): Int {
        val wv = webView ?: return 0
        if (!wv.canGoBack()) return 0

        val history = wv.copyBackForwardList()
        val currentIndex = history.currentIndex
        if (currentIndex <= 0) return 0

        val currentHost = wv.url?.toUri()?.host
        val baseHost = webapp.baseUrl.toUri().host ?: return 1
        val prevHost = history.getItemAtIndex(currentIndex - 1)?.url?.toUri()?.host

        if (currentHost == baseHost && prevHost != baseHost) {
            return 0
        }

        return 1
    }

    private fun extractSandboxId(): Int? {
        val processName = Application.getProcessName()
        val match = Regex(":sandbox_(\\d+)$").find(processName)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private const val CHANNEL_ID = "webview_controls"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_RELOAD = "wtf.mazy.peel.RELOAD"
        private const val ACTION_HOME = "wtf.mazy.peel.HOME"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
