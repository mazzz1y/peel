package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.VerticalSwipeRefreshLayout
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.FloatingControlsView
import wtf.mazy.peel.ui.browser.SystemBarController
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.disableSystemBarContrastEnforcement
import wtf.mazy.peel.util.shareText

class ChromiumActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefreshLayout: VerticalSwipeRefreshLayout
    private var webappUuid: String? = null
    
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var floatingControls: FloatingControlsView? = null
    private var isControlsHidden = false

    private val systemBarController by lazy {
        SystemBarController(
            window = window,
            getThemeColor = {
                val tv = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerLow, tv, true)
                tv.data
            },
            scrimColor = ContextCompat.getColor(this, R.color.floating_controls_scrim),
            setFullscreen = { /* Managed locally */ },
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private var tapCount = 0
            private var lastTapTime: Long = 0

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 400) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapTime = now

                if (tapCount == 3) {
                    toggleControlsVisibility()
                    tapCount = 0
                }
                return true
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableSystemBarContrastEnforcement()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chromium)

        webView = findViewById<WebView>(R.id.webview)
        progressBar = findViewById<ProgressBar>(R.id.progressBar)
        swipeRefreshLayout = findViewById<VerticalSwipeRefreshLayout>(R.id.swipeRefreshLayout)

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        
        ensureDataReady(webappUuid) {
            val url = intent.getStringExtra(Const.INTENT_TARGET_URL) ?: getWebapp()?.baseUrl ?: "about:blank"
            setupWebView()
            setupInsets()
            
            // Remove X-Requested-With header for initial load
            val headers = mutableMapOf<String, String>()
            headers["X-Requested-With"] = ""
            webView.loadUrl(url, headers)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        requestPermissionLauncher.launch(arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.RECORD_AUDIO
        ))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun ensureDataReady(uuid: String?, action: () -> Unit) {
        if (uuid == null) { action(); return }
        lifecycleScope.launch {
            DataManager.instance.ensureWebAppLoaded(uuid)
            action()
        }
    }

    private fun setupInsets() {
        val root = findViewById<View>(R.id.browser_root)
        val statusBarScrim = findViewById<View>(R.id.statusBarScrim)
        val navigationBarScrim = findViewById<View>(R.id.navigationBarScrim)
        val browserContent = findViewById<View>(R.id.browserContent)

        systemBarController.attach(statusBarScrim, navigationBarScrim, true)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusBarScrim?.layoutParams?.height = sys.top
            statusBarScrim?.requestLayout()
            navigationBarScrim?.layoutParams?.height = sys.bottom
            navigationBarScrim?.requestLayout()
            
            val isFs = (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
            val topPad = if (isFs) 0 else sys.top
            browserContent?.setPadding(0, topPad, 0, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        setupFloatingControls()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        floatingControls?.remove()
        floatingControls = null
    }

    private fun getWebapp(): WebApp? {
        return webappUuid?.let { DataManager.instance.getWebApp(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val webapp = getWebapp()
        val appSettings = webapp?.let { DataManager.instance.resolveEffectiveSettings(it) }

        // Clean Chrome User-Agent to bypass detection (Cloudflare/TTS)
        val chromeUA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"
        settings.userAgentString = chromeUA
        
        if (appSettings?.isShowFullscreen == true) {
            setFullscreen(true)
        }

        if (webapp != null && appSettings != null) {
            settings.javaScriptEnabled = appSettings.isAllowJs == true
            
            if (appSettings.isRequestDesktop == true) {
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
            }
            if (appSettings.isUseCustomUserAgent == true && !appSettings.customUserAgent.isNullOrBlank()) {
                settings.userAgentString = appSettings.customUserAgent
            }

            if (appSettings.isPullToRefresh == true) {
                swipeRefreshLayout.isEnabled = true
                swipeRefreshLayout.setOnRefreshListener {
                    webView.reload()
                    swipeRefreshLayout.isRefreshing = false
                }
            } else {
                swipeRefreshLayout.isEnabled = false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                // Early Injection: Hide WebView identity
                injectStealthJs()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                applyHiddenElements()
                injectThemeColorExtractor()
                // Late Injection: Wake up TTS and final spoof
                injectStealthJs()
                webView.evaluateJavascript("window.dispatchEvent(new Event('load'));", null)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // Removing X-Requested-With for subresources
                val headers = request?.requestHeaders?.toMutableMap() ?: mutableMapOf()
                if (headers.containsKey("X-Requested-With")) {
                    headers["X-Requested-With"] = ""
                }
                return null // Let WebView handle it with default headers (headers map here doesn't affect standard load)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("peel-hide://")) {
                    try {
                        val base64 = url.substring("peel-hide://".length)
                        val selector = String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
                        saveHiddenElement(selector)
                        Toast.makeText(this@ChromiumActivity, "Element Hidden permanently", Toast.LENGTH_SHORT).show()
                        webView.reload()
                    } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback
                (window.decorView as ViewGroup).addView(
                    customView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                webView.visibility = View.GONE
                setFullscreen(true)
            }

            override fun onHideCustomView() {
                (window.decorView as ViewGroup).removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE

                val webapp = getWebapp()
                val appSettings = webapp?.let { DataManager.instance.resolveEffectiveSettings(it) }
                if (appSettings?.isShowFullscreen != true) {
                    setFullscreen(false)
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            val cookie = CookieManager.getInstance().getCookie(url)
            request.addRequestHeader("Cookie", cookie)
            request.addRequestHeader("User-Agent", userAgent)
            request.setMimeType(mimetype)
            request.setDescription(getString(R.string.file_download))
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
            
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            NotificationUtils.showToast(this, getString(R.string.file_download))
        }
    }

    private fun injectThemeColorExtractor() {
        val js = "(function() { " +
                "  var meta = document.querySelector('meta[name=\"theme-color\"]');" +
                "  return meta ? meta.getAttribute('content') : null;" +
                "})();"
        webView.evaluateJavascript(js) { colorStr ->
            if (colorStr != null && colorStr != "null") {
                try {
                    val color = android.graphics.Color.parseColor(colorStr.replace("\"", ""))
                    systemBarController.update(color, color, 300L)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun setFullscreen(enabled: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun setupFloatingControls() {
        val uuid = webappUuid ?: return
        val root = findViewById<FrameLayout>(android.R.id.content)
        floatingControls = FloatingControlsView(
            parent = root,
            webappUuid = uuid,
            onHome = { finish() },
            onReload = { webView.reload() },
            onShare = { shareText(webView.url ?: "") },
            onFind = { /* Open Find In Page */ },
            onPicker = { enterElementPickerMode() },
            onExtensions = { showSiteOptionsDialog() },
            onReloadLongPress = { webView.clearCache(true); webView.reload() }
        )
        val webapp = getWebapp()
        if (webapp != null) {
            floatingControls?.setIncognito(webapp.resolvePrivateMode())
        }
        if (isControlsHidden) floatingControls?.setHidden(true)
    }

    private fun showSiteOptionsDialog() {
        val uuid = webappUuid ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Site Options")
            .setMessage("Manage hidden elements for this site:")
            .setPositiveButton("Clear All Hidden") { _, _ ->
                getSharedPreferences("peel_hidden_elements", Context.MODE_PRIVATE).edit().remove(uuid).apply()
                Toast.makeText(this, "Hidden elements cleared. Reloading...", Toast.LENGTH_SHORT).show()
                webView.reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleControlsVisibility() {
        isControlsHidden = !isControlsHidden
        floatingControls?.setHidden(isControlsHidden)
        Toast.makeText(this, if (isControlsHidden) "Controls Hidden (Triple-tap to show)" else "Controls Visible", Toast.LENGTH_SHORT).show()
    }

    private fun enterElementPickerMode() {
        val js = """
            (function() {
                const overlay = document.createElement('div');
                overlay.id = 'peel-picker-overlay';
                overlay.style.position = 'fixed';
                overlay.style.top = '0';
                overlay.style.left = '0';
                overlay.style.width = '100%';
                overlay.style.height = '100%';
                overlay.style.zIndex = '999999';
                overlay.style.cursor = 'crosshair';
                overlay.style.background = 'rgba(0,0,255,0.1)';
                document.body.appendChild(overlay);

                let selectedEl = null;

                overlay.onclick = function(e) {
                    overlay.style.pointerEvents = 'none';
                    const el = document.elementFromPoint(e.clientX, e.clientY);
                    overlay.style.pointerEvents = 'auto';

                    if (el && el !== overlay) {
                        if (selectedEl) selectedEl.style.outline = '';
                        selectedEl = el;
                        selectedEl.style.outline = '3px solid red';
                        
                        if (confirm('Hide this element permanently?')) {
                            const selector = getUniqueSelector(selectedEl);
                            document.body.removeChild(overlay);
                            selectedEl.style.display = 'none';
                            selectedEl.style.outline = '';
                            // Send selector back to Android
                            window.location.href = 'peel-hide://' + btoa(selector);
                        }
                    }
                };

                function getUniqueSelector(el) {
                    if (el.id) return '#' + el.id;
                    let path = el.tagName.toLowerCase();
                    if (el.className) path += '.' + el.className.trim().split(/\s+/).join('.');
                    return path;
                }
                
                alert('Element Picker: Tap an element to hide it.');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun applyHiddenElements() {
        val uuid = webappUuid ?: return
        val prefs = getSharedPreferences("peel_hidden_elements", Context.MODE_PRIVATE)
        val selectors = prefs.getStringSet(uuid, emptySet()) ?: emptySet()
        if (selectors.isEmpty()) return

        val css = selectors.joinToString(", ") { "$it { display: none !important; }" }
        val js = "const s = document.createElement('style'); s.innerHTML = `$css`; document.head.appendChild(s);"
        webView.evaluateJavascript(js, null)
    }

    private fun saveHiddenElement(selector: String) {
        val uuid = webappUuid ?: return
        val prefs = getSharedPreferences("peel_hidden_elements", Context.MODE_PRIVATE)
        
        // Fix: Use a single transaction to force persistence
        val selectors = prefs.getStringSet(uuid, emptySet())?.toMutableSet() ?: mutableSetOf()
        selectors.add(selector)
        
        prefs.edit()
            .remove(uuid)
            .putStringSet(uuid, selectors)
            .apply()
    }

    private fun injectStealthJs() {
        val stealthJs = """
            (function() {
                // Cloak navigator
                const mask = {
                    webdriver: false,
                    vendor: 'Google Inc.',
                    platform: 'Linux armv8l',
                    languages: ['en-US', 'en'],
                    deviceMemory: 8,
                    hardwareConcurrency: 8
                };
                for (const key in mask) {
                    Object.defineProperty(navigator, key, { get: () => mask[key] });
                }
                
                // Cloak window
                window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} };
                
                // Wake up TTS / DOM events
                window.dispatchEvent(new Event('load'));
                document.dispatchEvent(new Event('DOMContentLoaded'));
            })();
        """.trimIndent()
        webView.evaluateJavascript(stealthJs, null)
    }
}
