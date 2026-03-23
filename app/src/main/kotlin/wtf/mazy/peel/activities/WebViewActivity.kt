package wtf.mazy.peel.activities

import android.app.ActivityManager
import android.content.ActivityNotFoundException
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
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import wtf.mazy.peel.gecko.NestedGeckoView
import org.mozilla.geckoview.GeckoSession.Loader
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.media.MediaPlaybackManager
import wtf.mazy.peel.model.DataManager
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
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.WebViewLauncher
import wtf.mazy.peel.util.shortLabel
import wtf.mazy.peel.webview.DownloadHandler
import wtf.mazy.peel.webview.NavigationStartPoint
import wtf.mazy.peel.webview.PeelContentDelegate
import wtf.mazy.peel.webview.PeelNavigationDelegate
import wtf.mazy.peel.webview.PeelPermissionDelegate
import wtf.mazy.peel.webview.PeelProgressDelegate
import wtf.mazy.peel.webview.PeelPromptDelegate
import wtf.mazy.peel.webview.PermissionResult
import wtf.mazy.peel.webview.SessionHost
import wtf.mazy.peel.webview.WebViewContextMenu

class WebViewActivity : AppCompatActivity(), SessionHost {
    override var webappUuid: String? = null

    private var geckoView: NestedGeckoView? = null
    private var geckoSession: GeckoSession? = null

    private var progressBar: ProgressBar? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    override var currentlyReloading = true
    private var customHeaders: Map<String, String>? = null
    override var filePathCallback: ((Array<Uri>?) -> Unit)? = null
    override var canGoBack = false

    private val filePickerLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            if (result?.resultCode == RESULT_OK) {
                callback.invoke(extractUris(result.data))
            } else {
                callback.invoke(null)
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

    private val webapp: WebApp
        get() = DataManager.instance.getWebApp(webappUuid!!)!!

    private var floatingControls: FloatingControlsView? = null
    private lateinit var downloadHandler: DownloadHandler
    private lateinit var navigationDelegate: PeelNavigationDelegate
    private lateinit var permissionDelegate: PeelPermissionDelegate
    private lateinit var promptDelegate: PeelPromptDelegate
    private var contextMenu: WebViewContextMenu? = null

    private var pageLoadHandled = false
    private var mediaPlaybackManager: MediaPlaybackManager? = null
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
            },
            onFailure = { finish() },
        )
    }

    private val autoReloadController = AutoReloadController(
        mainHandler = mainHandler,
        onReload = {
            currentlyReloading = true
            geckoSession?.reload()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        liveInstances.add(this)

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

        _navigationStartPoint = NavigationStartPoint(webapp.baseUrl)
        cachedSettings = DataManager.instance.resolveEffectiveSettings(webapp)
        applyTaskSnapshotProtection()
        setupGeckoView()
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
        geckoSession?.setActive(true)
        mediaPlaybackManager?.setBackground(false)
        applyColorScheme()

        if (effectiveSettings.isShowNotification == true && floatingControls == null) {
            floatingControls = FloatingControlsView(
                parent = findViewById(R.id.webview_root),
                webappUuid = uuid,
                getSession = { geckoSession },
                onHome = {
                    _navigationStartPoint.reset()
                    loadURL(webapp.baseUrl)
                },
            )
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
        if (!isStartupComplete) return
        val settings = effectiveSettings
        floatingControls?.remove()
        floatingControls = null

        if (settings.isAllowMediaPlaybackInBackground == true) {
            mediaPlaybackManager?.setBackground(true)
        } else {
            geckoSession?.setActive(false)
        }

        if (settings.isClearCache == true) {
            val runtime = GeckoRuntimeProvider.getRuntime(this)
            runtime.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES)
        }

        autoReloadController.stop()
    }

    override fun onStop() {
        super.onStop()
        biometricController.onStop()
    }

    override fun onDestroy() {
        liveInstances.remove(this)
        launchOverlayController.release()
        biometricController.unregisterReceiver()
        systemBarController.release()
        mediaPlaybackManager?.release()
        mediaPlaybackManager = null
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
            cachedPeelApps = DataManager.instance.queryAllWebApps()
            if (!isFinishing && !isDestroyed) action()
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

    override val isDarkSchemeActive: Boolean
        get() {
            val mode = getDelegate().localNightMode
            if (mode == AppCompatDelegate.MODE_NIGHT_YES) return true
            if (mode == AppCompatDelegate.MODE_NIGHT_NO) return false
            val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return uiMode == Configuration.UI_MODE_NIGHT_YES
        }

    override fun applyColorScheme() {
        val settings = effectiveSettings
        val scheme = settings.colorScheme ?: WebAppSettings.COLOR_SCHEME_AUTO

        val nightMode = when (scheme) {
            WebAppSettings.COLOR_SCHEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            WebAppSettings.COLOR_SCHEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        getDelegate().localNightMode = nightMode

        geckoView?.setBackgroundColor(if (isDarkSchemeActive) Color.BLACK else Color.WHITE)
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
        val canBack = canGoBack && _navigationStartPoint.allowGoBack
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.site_not_found)
            .setMessage(getString(R.string.connection_error, description))
            .setPositiveButton(R.string.retry) { _, _ -> loadURL(url) }
            .setNegativeButton(if (canBack) R.string.back else R.string.exit) { _, _ ->
                if (canBack) geckoSession?.goBack() else finish()
            }
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
        permissionDelegate.clearPagePermissions()
        promptDelegate.clearAutoAuth()
        navigationDelegate.clearAutoAuth()
    }

    override fun onPageFullyLoaded() {
        if (pageLoadHandled || biometricController.isPromptActive) return
        pageLoadHandled = true
        if (launchOverlayController.isVisible) {
            launchOverlayController.hideFallback()
        }
        _navigationStartPoint.onPageFinished()
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
            .setOnCancelListener { onResult(PermissionResult.DENY) }
            .show()
    }

    private fun setupGeckoView() {
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
        if (settings.isShowFullscreen == true) systemBarController.hide() else systemBarController.show(false)
        applyColorScheme()
        customHeaders = buildCustomHeaders(settings)

        downloadHandler = DownloadHandler(
            activity = this,
            getSession = { geckoSession },
            getBaseUrl = { webapp.baseUrl },
        )

        val session = createSession(settings)
        geckoSession = session

        navigationDelegate = PeelNavigationDelegate(this)
        permissionDelegate = PeelPermissionDelegate(this)
        promptDelegate = PeelPromptDelegate(this)

        session.navigationDelegate = navigationDelegate
        if (settings.isLongClickShare == true) setupContextMenu(settings)
        val contextMenuCallback: ((GeckoSession, Int, Int, GeckoSession.ContentDelegate.ContextElement) -> Unit)? =
            if (contextMenu != null) {
                { s: GeckoSession, x: Int, y: Int, el: GeckoSession.ContentDelegate.ContextElement -> contextMenu?.onContextMenu(s, x, y, el) }
            } else null
        val contentDelegate = PeelContentDelegate(
            host = this,
            onDownload = { response -> downloadHandler.onExternalResponse(response) },
            onContextMenu = contextMenuCallback,
        )
        session.contentDelegate = contentDelegate
        session.progressDelegate = PeelProgressDelegate(this)
        session.permissionDelegate = permissionDelegate
        session.promptDelegate = promptDelegate

        val nestedView = geckoView as? wtf.mazy.peel.gecko.NestedGeckoView
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
        geckoView?.setSession(session)
        geckoView?.coverUntilFirstPaint(themeBackgroundColor)

        lifecycleScope.launch {
            if (settings.isDynamicStatusBar == true) {
                val ext = GeckoRuntimeProvider.ensureThemeColorExtension(applicationContext)
                if (ext != null) contentDelegate.setupThemeColorExtension(ext, session)
            }
            loadURL(sharedUrlFromIntent() ?: webapp.baseUrl)
            setupMediaPlayback(settings)
        }
    }

    private fun createSession(settings: WebAppSettings): GeckoSession {
        val useContainer = webapp.isUseContainer ||
                (webapp.groupUuid?.let { DataManager.instance.getGroup(it) }?.isUseContainer == true)
        val contextId = if (useContainer) webapp.uuid else null

        val sessionSettings = GeckoSessionSettings.Builder()
            .allowJavascript(settings.isAllowJs == true)
            .apply {
                if (settings.isRequestDesktop == true) {
                    userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                }
                if (contextId != null) contextId(contextId)
                usePrivateMode(
                    webapp.isEphemeralSandbox ||
                            (webapp.groupUuid?.let { DataManager.instance.getGroup(it) }?.isEphemeralSandbox == true)
                )
            }
            .build()

        val session = GeckoSession(sessionSettings)
        session.settings.useTrackingProtection = settings.isSafeBrowsing == true

        return session
    }

    private fun setupMediaPlayback(settings: WebAppSettings) {
        if (settings.isAllowMediaPlaybackInBackground != true) return
        val session = geckoSession ?: return
        val manager = MediaPlaybackManager(this)
        manager.attach(session, webapp.title, webapp.resolveIcon(), webapp.uuid)
        mediaPlaybackManager = manager
    }

    private fun setupContextMenu(settings: WebAppSettings) {
        contextMenu = WebViewContextMenu(
            activity = this,
            downloadHandler = downloadHandler,
            onExternalIntent = ::startExternalIntent,
            onOpenInPeel = ::openInPeel,
            onOpenInBestPeelMatch = ::openInBestPeelMatch,
            bestPeelMatchIcon = ::bestPeelMatchIcon,
            onToast = ::showToast,
        )
    }

    private fun applyWindowFlags(settings: WebAppSettings) {
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
        geckoView = findViewById(R.id.geckoview)
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
                setOnRefreshListener {
                    geckoSession?.reload()
                    isRefreshing = false
                }
            }
        } else {
            swipeRefreshLayout?.isEnabled = false
        }
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
                    if (canGoBack && _navigationStartPoint.allowGoBack) {
                        geckoSession?.goBack()
                    } else {
                        finishAndRemoveTask()
                    }
                }
            },
        )
    }

    private fun sharedUrlFromIntent(): String? =
        intent.getStringExtra(Const.INTENT_TARGET_URL)

    private fun extractUris(intent: Intent?): Array<Uri>? =
        intent?.data?.let { arrayOf(it) }
            ?: intent?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }

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

        private val liveInstances = mutableSetOf<WebViewActivity>()

        fun finishByUuid(uuid: String) {
            liveInstances.filter { it.webappUuid == uuid }.forEach { it.finish() }
        }

        fun finishAll() {
            liveInstances.toList().forEach { it.finish() }
        }
    }
}
