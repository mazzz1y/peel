package wtf.mazy.peel.browser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.ExtensionStateListener
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.gecko.GeckoRuntimeProvider.awaitVoid
import wtf.mazy.peel.gecko.NestedGeckoView
import wtf.mazy.peel.gecko.VerticalSwipeRefreshLayout
import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.FindInPageView
import wtf.mazy.peel.ui.FloatingControlsView
import wtf.mazy.peel.ui.browser.PullToRefreshController
import wtf.mazy.peel.ui.browser.SystemBarController
import wtf.mazy.peel.ui.browser.VirtualCursorController
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.ui.controls.BarControlsView
import wtf.mazy.peel.ui.controls.BrowserControls
import wtf.mazy.peel.ui.controls.ControlAction
import wtf.mazy.peel.ui.controls.ControlCallbacks
import wtf.mazy.peel.ui.controls.PanelControlsView
import wtf.mazy.peel.ui.dialog.DateTimePickerRequest
import wtf.mazy.peel.ui.dialog.DateTimePickerSession
import wtf.mazy.peel.ui.dialog.DialogContent
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.ui.dialog.ExternalLinkResult
import wtf.mazy.peel.ui.dialog.InitialSelection
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.TranslateDialog
import wtf.mazy.peel.ui.dialog.showDateTimePickerDialog
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.extensions.ExtensionActionController
import wtf.mazy.peel.ui.extensions.ExtensionPickerDialog
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.applyToolbarScreenInsets
import wtf.mazy.peel.util.copyToClipboard
import wtf.mazy.peel.util.deleteFilesOlderThan
import wtf.mazy.peel.util.disableSystemBarContrastEnforcement
import wtf.mazy.peel.util.isAutomotiveHost
import wtf.mazy.peel.util.isSingleWindowHost
import wtf.mazy.peel.util.isTelevisionHost
import wtf.mazy.peel.util.shareText
import wtf.mazy.peel.util.toast
import java.io.File

abstract class BaseSessionHost : PeelActivity(), SessionHost, TranslationHost {

    protected var geckoSession: GeckoSession? = null
    protected var lastSessionState: GeckoSession.SessionState? = null
    private var pendingBack: (() -> Unit)? = null
    protected var geckoView: GeckoView? = null
    protected var progressBar: ProgressBar? = null
    protected var swipeRefreshLayout: VerticalSwipeRefreshLayout? = null
    protected var appBar: AppBarLayout? = null
    protected var toolbar: MaterialToolbar? = null
    protected var statusBarScrim: View? = null
    protected var navigationBarScrim: View? = null
    protected var browserRoot: View? = null
    protected var browserContent: View? = null
    protected var panelControls: FrameLayout? = null
    protected var browserControls: BrowserControls? = null
    protected var appliedControlsMode: Int? = null
    private var browserControlsFullscreen = false
    protected var findInPage: FindInPageView? = null
    private var virtualCursor: VirtualCursorController? = null
    protected var isFullscreen: Boolean = false
    private var controlsGesture = false
    protected lateinit var navigationDelegate: PeelNavigationDelegate
    protected lateinit var downloadHandler: DownloadHandler
    private val pendingPermissionCallbacks = ArrayDeque<(Boolean) -> Unit>()

    override var canGoBack: Boolean = false
    override var lastLoadedUrl: String = ""
    override var currentlyReloading: Boolean = false
    override var filePathCallback: ((Array<Uri>?) -> Unit)? = null
    override var pendingCaptureFile: File? = null

    private var lastTopBarColor: Int? = null
    private var lastBottomBarColor: Int? = null
    private var connectionErrorDialog: AlertDialog? = null

    override fun applyWebOrientation(orientation: Int): Boolean {
        if (isSingleWindowHost()) return false
        requestedOrientation = orientation
        return true
    }

    protected abstract val showToolbar: Boolean
    protected abstract val isBrowserHost: Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableSystemBarContrastEnforcement()
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(themeBackgroundColor.toDrawable())
        setupSessionHostLayout(showToolbar)
        SessionHostRegistry.register(this, ownerWebAppUuid, isBrowserHost)
    }

    protected open val tracksExtensionState: Boolean = true

    override fun onStart() {
        super.onStart()
        if (tracksExtensionState) GeckoRuntimeProvider.addExtensionStateListener(
            extensionStateListener
        )
    }

    override fun onStop() {
        if (tracksExtensionState) GeckoRuntimeProvider.removeExtensionStateListener(
            extensionStateListener
        )
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        GeckoRuntimeProvider.getRuntime(this).orientationChanged(newConfig.orientation)
    }

    override val hostContext: Context get() = this
    override val hostScope: CoroutineScope get() = lifecycleScope
    override val hostResources: Resources get() = resources

    protected val themeBackgroundColor: Int
        get() {
            val tv = TypedValue()
            theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                tv,
                true
            )
            return tv.data
        }

    protected val filePickerLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            onFilePickerResult(result?.resultCode, result?.data)
        }

    protected val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.isNotEmpty() && results.values.all { it }
            pendingPermissionCallbacks.removeFirstOrNull()?.invoke(granted)
        }

    override fun runOnUi(action: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) action()
    }

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
        pendingPermissionCallbacks.addLast(onResult)
        try {
            permissionLauncher.launch(permissions)
        } catch (_: Exception) {
            pendingPermissionCallbacks.removeLast()
            onResult(false)
        }
    }

    override fun hasPermissions(vararg permissions: String): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun showDateTimePicker(
        request: DateTimePickerRequest,
        onResult: (String) -> Unit,
        onCancel: () -> Unit,
    ): DateTimePickerSession = showDateTimePickerDialog(this, request, onResult, onCancel)

    override fun showPermissionDialog(
        message: CharSequence,
        allowRemember: Boolean,
        allowRememberAlways: Boolean,
        onShown: (() -> Unit)?,
        onResult: (result: PermissionResult, remember: Boolean, always: Boolean) -> Unit,
    ) {
        val content = DialogContent.of(this).message(message)
        val remember = if (allowRemember) {
            MaterialCheckBox(this).apply {
                setText(R.string.permission_prompt_remember)
            }.also { content.add(it) }
        } else null

        val always = if (remember != null && allowRememberAlways) {
            MaterialCheckBox(this).apply {
                setText(R.string.permission_prompt_remember_always)
                isVisible = false
            }.also {
                content.add(it)
                it.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = 0 }
            }
        } else null

        if (always != null) {
            remember?.setOnCheckedChangeListener { _, isChecked ->
                always.isVisible = isChecked
                if (!isChecked) always.isChecked = false
            }
        }

        val answer = { result: PermissionResult ->
            onResult(result, remember?.isChecked == true, always?.isChecked == true)
        }

        MaterialAlertDialogBuilder(this)
            .setView(content.view)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_prompt_allow) { dialog, _ ->
                dialog.dismiss()
                answer(PermissionResult.ALLOW)
            }
            .setNegativeButton(R.string.permission_prompt_deny) { dialog, _ ->
                dialog.dismiss()
                answer(PermissionResult.DENY)
            }
            .create()
            .apply { setOnShowListener { onShown?.invoke() } }
            .show()
    }

    override fun startExternalIntent(uri: Uri) {
        val url = uri.toString()
        val intent = parseIntentUri(url)
            ?: runCatching { Intent(Intent.ACTION_VIEW, uri) }.getOrNull()
        if (intent != null && tryStartActivity(intent)) return
        showNoAppFound()
    }

    override fun openIncognito(url: String) {
        BrowserLauncher.launchIncognito(this, url)
    }

    override fun shareUrl(url: String) {
        shareText(url)
    }

    override fun copyLink(url: String) {
        copyToClipboard(url)
    }

    override fun dismissRedirectToFallback(fallback: String) {
        if (canGoBack) geckoSession?.goBack() else loadURL(fallback)
    }

    override fun loadURL(url: String) {
        geckoSession?.loadUri(effectiveSettings.upgradeUrl(url))
    }

    override val translationScope: CoroutineScope get() = lifecycleScope
    override val translationSettings: EffectiveSettings get() = effectiveSettings
    override val translationLoader: LoadingDialogController by lazy { LoadingDialogController(this) }

    override fun setTranslateButtonActive(active: Boolean) {
        browserControls?.setTranslateActive(active)
    }

    protected var translationDelegate: PeelTranslationDelegate? = null
    protected var translationsSupported: Boolean = false

    private val extensionStateListener = ExtensionStateListener { event ->
        val session = geckoSession ?: return@ExtensionStateListener
        extensionActions.attach(session)
        ExtensionActionController.extensionsChanged = false
        if (event.requiresReload) session.reload()
    }

    protected fun loadTranslationSupport() {
        lifecycleScope.launch {
            val supported = TranslationLanguages.isEngineSupported()
            if (supported == translationsSupported) return@launch
            translationsSupported = supported
            if (browserControls != null) rebuildBrowserControls()
        }
    }

    private val applyDynamicStatusBar: Boolean
        get() = effectiveSettings.dynamicStatusBar

    protected val systemBarController by lazy {
        SystemBarController(
            window = window,
            getThemeColor = ::themeBackgroundColor,
            scrimColor = ContextCompat.getColor(this, R.color.floating_controls_scrim),
            setFullscreen = { isFullscreen = it },
        )
    }

    protected val pullToRefreshController by lazy {
        PullToRefreshController(
            layout = swipeRefreshLayout,
            onRefresh = ::reloadCurrentPage,
            canOverscrollTop = { (geckoView as? NestedGeckoView)?.canOverscrollTop == true },
        )
    }

    protected fun attachSystemBars() {
        systemBarController.attach(statusBarScrim, navigationBarScrim, applyDynamicStatusBar)
    }

    protected open fun updateSystemBarColors(top: Int, bottom: Int) {
        val effectiveBottom = if (hasPanelControls) themeBackgroundColor else bottom
        systemBarController.update(top, effectiveBottom, UI_ANIMATION_DURATION_MS)
    }

    private val hasPanelControls: Boolean
        get() = appliedControlsMode == WebAppSettings.BROWSER_CONTROLS_PANEL

    protected val extensionActions by lazy {
        ExtensionActionController(
            activity = this,
            onExtensionsReady = { _ -> if (browserControls != null) rebuildBrowserControls() },
        )
    }

    protected fun homeAction() {
        ownerWebAppUuid?.let { SessionHostRegistry.finishPopupsOwnedBy(it) }
        navigateHome()
    }

    protected open fun navigateHome() = Unit

    protected fun rebuildBrowserControls() {
        if (browserControls == null) return
        replaceBrowserControls(controlsMode)
    }

    protected fun openTranslateDialog() {
        val session = geckoSession ?: return
        val state = translationDelegate?.lastTranslationState
        val activePair = state?.requestedTranslationPair
        val docLang = state?.detectedLanguages?.docLangTag
        val configuredTarget = if (activePair == null && !docLang.isNullOrBlank() &&
            effectiveSettings.translatorEnabled
        ) {
            TranslationLanguages.resolveConfiguredTarget(
                effectiveSettings.autoTranslatePairs,
                docLang
            )
        } else null
        val prefill = TranslateDialog.Prefill(
            fromCode = activePair?.fromLanguage ?: docLang,
            toCode = activePair?.toLanguage ?: configuredTarget,
            showOriginal = activePair != null,
        )
        TranslateDialog.show(this, session, translationDelegate, prefill)
    }

    protected fun onTranslateLongPress() {
        val session = geckoSession ?: return
        val delegate = translationDelegate ?: run { openTranslateDialog(); return }
        if (delegate.isPageTranslated) {
            delegate.restoreOriginal(session)
            return
        }
        val docLang = delegate.lastTranslationState?.detectedLanguages?.docLangTag
        val target = delegate.resolveLongPressTarget(docLang)
        if (target != null && !docLang.isNullOrBlank()) {
            delegate.translateToTarget(session, docLang, target)
        } else {
            openTranslateDialog()
        }
    }

    protected fun clearSiteCacheAndReload() {
        val host = runCatching { lastLoadedUrl.toUri().host }.getOrNull()
        val flags = StorageController.ClearFlags.NETWORK_CACHE or
                StorageController.ClearFlags.IMAGE_CACHE
        val storage = GeckoRuntimeProvider.getRuntime(this).storageController
        lifecycleScope.launch {
            if (!host.isNullOrBlank()) {
                try {
                    storage.clearDataFromBaseDomain(host, flags).awaitVoid()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
            toast(R.string.cache_cleared, long = true)
            // BYPASS_PROXY skips intermediate proxy caches, not proxy routing: it maps to
            // LOAD_BYPASS_CACHE | LOAD_FRESH_CONNECTION, and proxy.onRequest still applies.
            geckoSession?.reload(
                GeckoSession.LOAD_FLAGS_BYPASS_CACHE or GeckoSession.LOAD_FLAGS_BYPASS_PROXY
            )
        }
    }

    protected suspend fun setupThemeColorExtensionIfEnabled() {
        if (!effectiveSettings.dynamicStatusBar) return
        val session = geckoSession ?: return
        val delegate = session.contentDelegate as? PeelContentDelegate ?: return
        val ext = GeckoRuntimeProvider.ensureThemeColorExtension(applicationContext) ?: return
        delegate.setupThemeColorExtension(ext, session)
    }

    protected fun bindDelegates(
        session: GeckoSession,
        contentDelegate: PeelContentDelegate,
        permissionDelegate: PeelPermissionDelegate = PeelPermissionDelegate(this),
        promptDelegate: PeelPromptDelegate = PeelPromptDelegate(this),
    ) {
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        session.progressDelegate = PeelProgressDelegate(this)
        session.permissionDelegate = permissionDelegate
        session.promptDelegate = promptDelegate
        attachScrollDelegate(session)
        SessionHostRegistry.bindContext(this, sessionContextId)
    }

    protected fun displaySession(session: GeckoSession) {
        geckoSession = session
        reattachSessionToView()
    }

    protected fun reattachSessionToView() {
        val session = geckoSession ?: return
        val view = geckoView ?: return
        if (view.session != session) {
            view.setSession(session)
            view.coverUntilFirstPaint(themeBackgroundColor)
        }
    }

    override fun onSessionStateUpdated(state: GeckoSession.SessionState) {
        lastSessionState = state
        val onNoTarget = pendingBack ?: return
        pendingBack = null
        resolveBack(state, onNoTarget)
    }

    protected fun goBackOrElse(onNoTarget: () -> Unit) {
        val session = geckoSession ?: return onNoTarget()
        if (!canGoBack) return onNoTarget()
        if (effectiveSettings.skipHistoryDomains.isEmpty()) return session.goBack()
        if (pendingBack != null) return
        // Session state reaches us on a 10 s timer (browser.sessionstore.interval), so whatever
        // is cached is stale at the moment Back is pressed; the flush forces a fresh snapshot.
        pendingBack = onNoTarget
        session.flushSessionState()
    }

    private fun resolveBack(state: GeckoSession.SessionState, onNoTarget: () -> Unit) {
        val session = geckoSession ?: return onNoTarget()
        val history = HistorySnapshot.from(state) ?: return session.goBack()
        val skip = effectiveSettings.skipHistoryDomains
        when (val step = HistorySkip.step(history, HistorySkip.BACKWARD, skip)) {
            is HistoryStep.GoTo -> session.gotoHistoryIndex(step.index)
            HistoryStep.Exhausted -> onNoTarget()
            HistoryStep.Unchanged -> session.goBack()
        }
    }

    override fun goBackOrFinish() {
        goBackOrElse { finish() }
    }

    override fun onWindowCloseRequest() {
        if (navigationDelegate.isOnJumpHost) goBackOrFinish() else finish()
    }

    override val policyOrigin: String
        get() = baseUrl

    override fun onInitialNavigationDenied() = Unit

    protected open val ownerWebAppUuid: String? = null
    protected open val activeTranslateTarget: String? = null

    override fun openPopupSession(): GeckoResult<GeckoSession> {
        val popup = createSession(effectiveSettings)
        val key = SessionHandoff.put(popup)
        val launched = runCatching {
            startActivity(
                PopupLaunch.intent(
                    context = this,
                    key = key,
                    title = webAppName,
                    settings = effectiveSettings,
                    contextId = sessionContextId,
                    privateMode = sessionPrivateMode,
                    ownerWebAppUuid = ownerWebAppUuid,
                    translateTarget = activeTranslateTarget,
                    policyOrigin = policyOrigin,
                )
            )
        }.isSuccess
        if (!launched) {
            SessionHandoff.take(key)?.session?.close()
            return GeckoResult.fromValue(null)
        }
        return GeckoResult.fromValue(popup)
    }

    override fun markCurrentPageAsJumpHost() {
        navigationDelegate.markCurrentPageAsJumpHost()
    }

    override fun resetSystemBarColorsForNewPage() {
        lastTopBarColor = null
        lastBottomBarColor = null
    }

    protected fun restoreSystemBarColors() {
        updateSystemBarColors(
            lastTopBarColor ?: themeBackgroundColor,
            lastBottomBarColor ?: themeBackgroundColor,
        )
    }

    override fun reportSystemBarColorsFromContent(
        topCandidates: List<Int>,
        bottomCandidates: List<Int>,
        metaThemeColor: Int?,
    ) {
        val top = resolveBarColor(topCandidates, metaThemeColor, lastTopBarColor)
        val bottom = resolveBarColor(bottomCandidates, metaThemeColor, lastBottomBarColor)
        if (top != null) lastTopBarColor = top
        if (bottom != null) lastBottomBarColor = bottom
        updateSystemBarColors(top ?: themeBackgroundColor, bottom ?: themeBackgroundColor)
    }

    private fun resolveBarColor(candidates: List<Int>, meta: Int?, lastGood: Int?): Int? {
        for (color in candidates) {
            if (android.graphics.Color.alpha(color) == 0xFF) return color
        }
        if (meta != null) return meta
        return lastGood
    }

    override fun showConnectionError(description: String, url: String, onRetry: (() -> Unit)?) {
        val committed = navigationDelegate.lastLocation
        val hasPreviousPage = lastLoadedUrl.isNotBlank()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.site_not_found)
            .setMessage(getString(R.string.connection_error, description))
            .setPositiveButton(R.string.retry) { _, _ -> onRetry?.invoke() ?: loadURL(url) }
        when {
            hasPreviousPage && committed == lastLoadedUrl && committed != url ->
                builder.setNegativeButton(R.string.back) { _, _ ->
                    geckoSession?.reload()
                }.setCancelable(false)

            canGoBack -> builder.setNegativeButton(R.string.back) { _, _ ->
                goBackOrFinish()
            }.setCancelable(false)

            else -> builder.setNegativeButton(R.string.exit) { _, _ ->
                finish()
            }.setCancelable(false)
        }
        connectionErrorDialog?.dismiss()
        connectionErrorDialog = builder.show().also { dialog ->
            dialog.setOnDismissListener {
                if (connectionErrorDialog === dialog) connectionErrorDialog = null
            }
        }
    }

    override fun showHttpAuthDialog(
        onResult: (username: String, password: String) -> Unit,
        onCancel: () -> Unit,
        url: String?,
    ): AlertDialog {
        var passwordInput: TextInputEditText? = null
        val dp8 = (resources.displayMetrics.density * 8).toInt()
        return showInputDialogRaw(
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

    override fun showTextPromptDialog(
        title: String?,
        message: String?,
        defaultValue: String?,
        onResult: (String) -> Unit,
        onCancel: () -> Unit,
    ): AlertDialog = showInputDialogRaw(
        InputDialogConfig(
            title = title,
            allowEmpty = true,
            prefill = defaultValue.orEmpty(),
            message = message,
            initialSelection = InitialSelection.CURSOR_AT_END,
            onCancel = { onCancel() },
        ),
    ) { input, _ ->
        onResult(input.text.toString())
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

    override fun findPeelAppMatches(url: String): List<WebApp> =
        ExternalLinkMenu.findPeelAppMatches(externalLinkPeelApps, url, externalLinkExcludeUuid)

    override fun onPageFullyLoaded() {
        lastLoadedUrl = navigationDelegate.lastLocation
        connectionErrorDialog?.dismiss()
        navigationDelegate.onPageLoadFinished()
    }

    override fun onPageLoadEnded() {
        pullToRefreshController.stopRefreshing()
    }

    override fun onDestroy() {
        virtualCursor?.release()
        virtualCursor = null
        connectionErrorDialog?.dismiss()
        connectionErrorDialog = null
        if (::navigationDelegate.isInitialized) navigationDelegate.cancelPendingPrompts()
        extensionActions.detach()
        systemBarController.release()
        SessionHostRegistry.unregister(this)
        super.onDestroy()
    }

    protected fun closeGeckoSession() {
        pendingBack = null
        (geckoSession?.progressDelegate as? PeelProgressDelegate)?.release()
        geckoView?.releaseSession()
        geckoSession?.close()
        geckoSession = null
    }

    protected val isWebFullscreen: Boolean
        get() = (geckoSession?.contentDelegate as? PeelContentDelegate)?.isWebFullscreen == true

    protected fun exitFullscreenIfActive(): Boolean {
        if (!isWebFullscreen) return false
        (geckoSession?.contentDelegate as? PeelContentDelegate)?.exitFullscreen()
        return true
    }

    protected fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    protected fun showNoAppFound() {
        toast(R.string.no_app_found, long = true)
    }

    private fun consumeCaptureUri(): Uri? {
        val file = pendingCaptureFile
        pendingCaptureFile = null
        return file?.let { Uri.fromFile(it) }
    }

    private fun onFilePickerResult(resultCode: Int?, data: Intent?) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        if (resultCode != RESULT_OK) {
            consumeCaptureUri()
            callback.invoke(null)
            return
        }
        val contentUris = extractUris(data)
        if (contentUris.isNullOrEmpty()) {
            val captured = consumeCaptureUri()
            callback.invoke(captured?.let { arrayOf(it) })
            return
        }
        lifecycleScope.launch {
            val fileUris = withContext(Dispatchers.IO) { resolveToFileUris(contentUris) }
            if (fileUris.isNotEmpty()) {
                callback.invoke(fileUris)
            } else {
                val captured = consumeCaptureUri()
                callback.invoke(captured?.let { arrayOf(it) })
            }
        }
    }

    private fun extractUris(intent: Intent?): Array<Uri>? =
        intent?.data?.let { arrayOf(it) }
            ?: intent?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }

    private fun resolveToFileUris(uris: Array<Uri>): Array<Uri> {
        val picksDir = File(cacheDir, "picks").apply { mkdirs() }
        picksDir.deleteFilesOlderThan()
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
        browserRoot = findViewById(R.id.browser_root)
        browserContent = findViewById(R.id.browserContent)
        appBar = findViewById(R.id.appBar)
        toolbar = findViewById(R.id.toolbar)
        statusBarScrim = findViewById(R.id.statusBarScrim)
        navigationBarScrim = findViewById(R.id.navigationBarScrim)
        panelControls = findViewById(R.id.panelControls)
        panelControls?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) requestInsetsUpdate()
        }

        if (showToolbar) installToolbarInsetsListener()
        else installEdgeToEdgeInsetsListener()

        if (isTelevisionHost()) installVirtualCursor()
    }

    private fun installVirtualCursor() {
        virtualCursor = VirtualCursorController(
            content = browserContent as FrameLayout,
            dispatchTouch = ::dispatchTouchEvent,
            onBackPressedDispatcher = onBackPressedDispatcher,
            onScroll = { dx, dy ->
                geckoSession?.panZoomController?.scrollBy(
                    ScreenLength.fromPixels(dx.toDouble()),
                    ScreenLength.fromPixels(dy.toDouble()),
                    PanZoomController.SCROLL_BEHAVIOR_AUTO,
                )
            },
            exitFullscreenIfActive = ::exitFullscreenIfActive,
        )
    }

    private fun installEdgeToEdgeInsetsListener() {
        val root = browserRoot ?: return
        val insetTypes = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(insetTypes)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusBarScrim?.let {
                it.layoutParams.height = sys.top
                it.requestLayout()
            }
            navigationBarScrim?.let {
                it.layoutParams.height = sys.bottom
                it.requestLayout()
            }
            // the cutout inset survives a system-bar hide, so it has to be dropped explicitly
            val content = if (isFullscreen) {
                insets.getInsets(WindowInsetsCompat.Type.systemBars())
            } else {
                sys
            }
            val leftPad = content.left
            val rightPad = content.right
            val topPad = content.top
            val systemBottom = maxOf(content.bottom, ime.bottom)
            panelControls?.let {
                val lp = it.layoutParams as FrameLayout.LayoutParams
                if (lp.leftMargin != leftPad || lp.rightMargin != rightPad ||
                    lp.bottomMargin != systemBottom
                ) {
                    lp.leftMargin = leftPad
                    lp.rightMargin = rightPad
                    lp.bottomMargin = systemBottom
                    it.requestLayout()
                }
            }
            applyBrowserContentInsets(leftPad, topPad, rightPad, systemBottom + panelControlsHeight)
            browserControls?.onImeVisibilityChanged(ime.bottom > 0)
            WindowInsetsCompat.CONSUMED
        }
    }

    // margins, not padding: padding would paint browserContent's own background into the inset
    private fun applyBrowserContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val content = browserContent ?: return
        val lp = content.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin == left && lp.topMargin == top &&
            lp.rightMargin == right && lp.bottomMargin == bottom
        ) {
            return
        }
        lp.leftMargin = left
        lp.topMargin = top
        lp.rightMargin = right
        lp.bottomMargin = bottom
        content.layoutParams = lp
    }

    private val panelControlsHeight: Int
        get() = browserControls?.reservedBottomHeight() ?: 0

    protected fun requestInsetsUpdate() {
        browserRoot?.let { it.post(it::requestApplyInsets) }
    }

    private fun installToolbarInsetsListener() {
        applyToolbarScreenInsets()
        browserContent?.applyBottomScreenInsets()
    }

    protected open val sessionContextId: String? = null
    protected open val sessionPrivateMode: Boolean = false

    protected fun createSession(settings: EffectiveSettings): GeckoSession {
        val sessionSettings = GeckoSessionSettings.Builder()
            .allowJavascript(settings.allowJs)
            .apply {
                if (settings.requestDesktop) {
                    userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                }
                if (settings.useCustomUserAgent && settings.customUserAgent.isNotBlank()) {
                    userAgentOverride(settings.customUserAgent)
                }
                sessionContextId?.let { contextId(it) }
                usePrivateMode(sessionPrivateMode)
            }
            .build()

        val session = GeckoSession(sessionSettings)
        session.settings.useTrackingProtection =
            settings.trackerProtection != WebAppSettings.TRACKER_PROTECTION_NONE
        return session
    }

    protected fun applyWindowFlags(settings: EffectiveSettings) {
        if (settings.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (settings.disableScreenshots) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    protected fun reloadCurrentPage() {
        val committed = navigationDelegate.lastLocation
        if (committed.isEmpty() || committed == "about:blank") {
            val fallback = lastLoadedUrl.ifBlank { baseUrl }
            if (fallback.isBlank()) pullToRefreshController.stopRefreshing()
            else loadURL(fallback)
        } else {
            geckoSession?.reload()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (virtualCursor?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) virtualCursor?.cancel()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val claimed = browserControls?.onHostTouchEvent(ev) == true
        if (claimed && !controlsGesture) {
            controlsGesture = true
            val cancel = MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (!controlsGesture) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            controlsGesture = false
        }
        return true
    }

    private fun cancelControlsGesture() {
        controlsGesture = false
    }

    protected fun attachScrollDelegate(session: GeckoSession) {
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                browserControls?.onContentScrolled(scrollY)
            }
        }
    }

    protected open val floatingControlsKey: String?
        get() = ownerWebAppUuid

    protected open fun shareCurrentPage() = shareText(lastLoadedUrl)

    protected open fun onShareLongPress(): (() -> Unit)? = null

    protected open fun onOpenAppList(): (() -> Unit)? = null

    protected open fun createBrowserControls(mode: Int): BrowserControls? {
        val floatingKey = floatingControlsKey ?: return null
        val translateEnabled =
            translationsSupported && effectiveSettings.translatorEnabled
        val actions = ControlCallbacks(
            onBack = if (isAutomotiveHost()) ({ onBackPressedDispatcher.onBackPressed() }) else null,
            onHome = if (ownerWebAppUuid != null) ({ homeAction() }) else null,
            onReload = ::reloadCurrentPage,
            onReloadLongPress = ::clearSiteCacheAndReload,
            onShare = ::shareCurrentPage,
            onShareLongPress = onShareLongPress(),
            onFind = ::openFindInPage,
            onTranslate = if (translateEnabled) ({ openTranslateDialog() }) else null,
            onTranslateLongPress = if (translateEnabled) ({ onTranslateLongPress() }) else null,
            onExtensions = if (ExtensionActionController.hasExtensions)
                ({ ExtensionPickerDialog.show(this, extensionActions) }) else null,
            onOpenAppList = onOpenAppList(),
        ).toList()
        return buildBrowserControls(mode, floatingKey, actions)
    }

    private fun buildBrowserControls(
        mode: Int,
        floatingKey: String,
        actions: List<ControlAction>,
    ): BrowserControls {
        val parent = findViewById<FrameLayout>(R.id.browserContent)
        val panel = panelControls
        return when {
            mode == WebAppSettings.BROWSER_CONTROLS_BAR ->
                BarControlsView(parent, actions)

            mode == WebAppSettings.BROWSER_CONTROLS_PANEL && panel != null ->
                PanelControlsView(panel, actions, ::requestInsetsUpdate)

            else -> FloatingControlsView(
                parent = parent,
                webAppUuid = floatingKey,
                actions = actions,
                onExpandedChange = { expanded, durationMs ->
                    systemBarController.setDim(expanded, durationMs)
                },
            )
        }.also { controls ->
            controls.setIncognito(sessionPrivateMode)
            if (translationsSupported) {
                controls.setTranslateActive(translationDelegate?.isPageTranslated == true)
            }
        }
    }

    protected val controlsMode: Int
        get() = effectiveSettings.browserControlsMode

    protected fun showBrowserControls() {
        val mode = controlsMode
        if (browserControls != null && appliedControlsMode == mode) return
        replaceBrowserControls(mode)
    }

    protected fun hideBrowserControls() {
        closeFindInPage()
        cancelControlsGesture()
        browserControls?.remove()
        browserControls = null
        appliedControlsMode = null
        browserControlsFullscreen = false
    }

    // collapsing the height, not the visibility: the scrolling-view behaviour offsets content by
    // the app bar's measured height, which a GONE app bar keeps
    protected fun setToolbarFullscreen(fullscreen: Boolean) {
        val bar = appBar ?: return
        val lp = bar.layoutParams
        val target = if (fullscreen) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height == target) return
        lp.height = target
        bar.layoutParams = lp
    }

    protected fun setBrowserControlsFullscreen(fullscreen: Boolean) {
        browserControlsFullscreen = fullscreen
        updateBrowserControlsVisibility()
        requestInsetsUpdate()
    }

    private fun replaceBrowserControls(mode: Int) {
        cancelControlsGesture()
        browserControls?.remove()
        appliedControlsMode = mode
        browserControls =
            if (mode == WebAppSettings.BROWSER_CONTROLS_OFF) null else createBrowserControls(mode)
        updateBrowserControlsVisibility()
        restoreSystemBarColors()
    }

    private fun updateBrowserControlsVisibility() {
        val hidden = browserControlsFullscreen || findInPage != null
        if (hidden) cancelControlsGesture()
        browserControls?.setHidden(hidden)
    }

    protected fun openFindInPage() {
        if (findInPage != null) return
        val session = geckoSession ?: return
        findInPage = FindInPageView(
            parent = findViewById(R.id.browserContent),
            window = window,
            session = session,
            onClose = {
                findInPage = null
                updateBrowserControlsVisibility()
            },
        )
        updateBrowserControlsVisibility()
    }

    protected fun closeFindInPage() {
        findInPage?.remove()
    }

    override val hostProgressBar: ProgressBar?
        get() = progressBar

    protected abstract val externalLinkExcludeUuid: String?
    protected abstract val externalLinkPeelApps: List<WebApp>
    protected abstract val externalLinkIncludeLoadHere: Boolean

    override fun onWebFullscreenEnter() {
        systemBarController.hide()
        closeFindInPage()
        setToolbarFullscreen(true)
        setBrowserControlsFullscreen(true)
        pullToRefreshController.setSuspended(true)
        updateBackCallbackEnabled()
    }

    override fun onWebFullscreenExit() {
        systemBarController.show(effectiveSettings.showFullscreen)
        setToolbarFullscreen(false)
        setBrowserControlsFullscreen(false)
        pullToRefreshController.setSuspended(false)
        updateBackCallbackEnabled()
    }

    protected open fun updateBackCallbackEnabled() = Unit

    private companion object {
        const val UI_ANIMATION_DURATION_MS = 300L
    }
}
