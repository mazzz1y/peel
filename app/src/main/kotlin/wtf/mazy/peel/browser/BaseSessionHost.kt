package wtf.mazy.peel.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.VerticalSwipeRefreshLayout
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.util.NotificationUtils
import java.io.File

abstract class BaseSessionHost : AppCompatActivity(), SessionHost {

    protected var geckoSession: GeckoSession? = null
    protected var geckoView: GeckoView? = null
    protected var progressBar: ProgressBar? = null
    protected var swipeRefreshLayout: VerticalSwipeRefreshLayout? = null
    protected var launchOverlay: View? = null
    protected var appBar: AppBarLayout? = null
    protected var toolbar: MaterialToolbar? = null
    protected var statusBarScrim: View? = null
    protected var navigationBarScrim: View? = null
    protected var browserContent: View? = null
    protected var isFullscreen: Boolean = false
    protected lateinit var navigationDelegate: PeelNavigationDelegate
    protected lateinit var downloadHandler: DownloadHandler
    protected var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    override var canGoBack: Boolean = false
    override var lastLoadedUrl: String = ""
    override var currentlyReloading: Boolean = false
    override var filePathCallback: ((Array<Uri>?) -> Unit)? = null

    override var hostOrientation: Int
        get() = requestedOrientation
        set(value) {
            requestedOrientation = value
        }
    override val hostWindow: Window get() = window

    override val themeBackgroundColor: Int
        get() {
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            return tv.data
        }

    protected val filePickerLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            onFilePickerResult(result?.resultCode, result?.data)
        }

    protected val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.isNotEmpty() && results.values.all { it }
            pendingPermissionCallback?.invoke(granted)
            pendingPermissionCallback = null
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

    override fun requestOsPermissions(
        permissions: Array<String>,
        onResult: (granted: Boolean) -> Unit,
    ) {
        pendingPermissionCallback = onResult
        permissionLauncher.launch(permissions)
    }

    override fun hasPermissions(vararg permissions: String): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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

    override fun startExternalIntent(uri: Uri) {
        val url = uri.toString()
        val intent = parseIntentUri(url)
            ?: runCatching { Intent(Intent.ACTION_VIEW, uri) }.getOrNull()
        if (intent != null && tryStartActivity(intent)) return
        showNoAppFound()
    }

    override fun dismissRedirectToFallback(fallback: String) {
        if (canGoBack) geckoSession?.goBack() else loadURL(fallback)
    }

    override fun loadURL(url: String) {
        val finalUrl = if (url.startsWith("http://") && effectiveSettings.isAlwaysHttps == true)
            url.replaceFirst("http://", "https://") else url
        geckoSession?.loadUri(finalUrl)
    }

    override fun goBackOrFinish() {
        if (canGoBack) geckoSession?.goBack() else finish()
    }

    override fun showConnectionError(description: String, url: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.site_not_found)
            .setMessage(getString(R.string.connection_error, description))
            .setPositiveButton(R.string.retry) { _, _ -> loadURL(url) }
            .setNegativeButton(if (canGoBack) R.string.back else R.string.exit) { _, _ ->
                goBackOrFinish()
            }
            .setCancelable(false)
            .show()
    }

    override fun showHttpAuthDialog(
        onResult: (username: String, password: String) -> Unit,
        onCancel: () -> Unit,
        url: String?,
    ) {
        var passwordInput: TextInputEditText? = null
        val dp8 = (resources.displayMetrics.density * 8).toInt()
        showInputDialogRaw(
            InputDialogConfig(
                titleRes = R.string.setting_basic_auth,
                hintRes = R.string.username,
                allowEmpty = true,
                message = url ?: "",
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
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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

    override fun showExternalLinkMenu(url: String, onResult: (ExternalLinkResult) -> Unit) {
        ExternalLinkMenu.show(
            activity = this,
            url = url,
            excludeUuid = externalLinkExcludeUuid,
            peelApps = externalLinkPeelApps,
            includeLoadHere = externalLinkIncludeLoadHere,
            onResult = onResult,
        )
    }

    override fun onPageFullyLoaded() {
        navigationDelegate.onPageLoadFinished()
    }

    protected fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    protected open fun showNoAppFound() {
        NotificationUtils.showToast(this, getString(R.string.no_app_found))
    }

    protected open fun onFilePickerResult(resultCode: Int?, data: Intent?) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        if (resultCode != RESULT_OK) {
            PeelPromptDelegate.consumeCaptureUri()
            callback.invoke(null)
            return
        }
        val contentUris = extractUris(data)
        if (contentUris.isNullOrEmpty()) {
            val captured = PeelPromptDelegate.consumeCaptureUri()
            callback.invoke(captured?.let { arrayOf(it) })
            return
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

    private fun extractUris(intent: Intent?): Array<Uri>? =
        intent?.data?.let { arrayOf(it) }
            ?: intent?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }

    private fun resolveToFileUris(uris: Array<Uri>): Array<Uri> {
        val picksDir = File(cacheDir, "picks").apply { mkdirs() }
        picksDir.listFiles()?.forEach { it.delete() }
        return uris.mapNotNull { uri ->
            if (uri.scheme == "file") return@mapNotNull uri
            try {
                val mimeType = contentResolver.getType(uri)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val dest = File.createTempFile("pick_", ".$ext", picksDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(dest)
            } catch (_: Exception) {
                null
            }
        }.toTypedArray()
    }

    protected fun setupSessionHostLayout(showToolbar: Boolean) {
        setContentView(
            if (showToolbar) R.layout.activity_extension_page
            else R.layout.activity_browser
        )
        geckoView = findViewById(R.id.geckoview)
        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        launchOverlay = findViewById(R.id.launchOverlay)
        browserContent = findViewById(R.id.browserContent)
        appBar = findViewById(R.id.appBar)
        toolbar = findViewById(R.id.toolbar)
        statusBarScrim = findViewById(R.id.statusBarScrim)
        navigationBarScrim = findViewById(R.id.navigationBarScrim)

        if (showToolbar) installToolbarInsetsListener()
        else installEdgeToEdgeInsetsListener()
    }

    private fun installEdgeToEdgeInsetsListener() {
        val root = findViewById<View>(R.id.browser_root) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusBarScrim?.let {
                it.layoutParams.height = sys.top
                it.requestLayout()
            }
            navigationBarScrim?.let {
                it.layoutParams.height = sys.bottom
                it.requestLayout()
            }
            val topPad = if (isFullscreen) 0 else sys.top
            val bottomPad = maxOf(sys.bottom, ime.bottom)
            browserContent?.setPadding(0, topPad, 0, bottomPad)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun installToolbarInsetsListener() {
        val content = browserContent ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, imeBottom)
            insets
        }
    }

    protected open val sessionContextId: String? = null
    protected open val sessionPrivateMode: Boolean = false

    protected fun createSession(settings: WebAppSettings): GeckoSession {
        val sessionSettings = GeckoSessionSettings.Builder()
            .allowJavascript(settings.isAllowJs == true)
            .apply {
                if (settings.isRequestDesktop == true) {
                    userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                }
                val customUa = settings.customUserAgent
                if (settings.isUseCustomUserAgent == true && !customUa.isNullOrBlank()) {
                    userAgentOverride(customUa)
                }
                sessionContextId?.let { contextId(it) }
                usePrivateMode(sessionPrivateMode)
            }
            .build()

        val session = GeckoSession(sessionSettings)
        session.settings.useTrackingProtection =
            settings.isSafeBrowsing != null && settings.isSafeBrowsing != WebAppSettings.TRACKER_PROTECTION_NONE
        return session
    }

    protected fun applyWindowFlags(settings: WebAppSettings) {
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

    protected fun setupPullToRefresh(settings: WebAppSettings) {
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

    protected open fun reloadCurrentPage() {
        geckoSession?.reload()
    }

    override val hostProgressBar: ProgressBar?
        get() = progressBar

    protected abstract val externalLinkExcludeUuid: String?
    protected abstract val externalLinkPeelApps: List<WebApp>
    protected abstract val externalLinkIncludeLoadHere: Boolean

    abstract override fun onWebFullscreenEnter()
    abstract override fun onWebFullscreenExit()
}
