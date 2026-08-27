package wtf.mazy.peel.browser

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.view.Window
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.dialog.DateTimePickerRequest
import wtf.mazy.peel.ui.dialog.DateTimePickerSession
import java.io.File

enum class PermissionResult {
    ALLOW,
    DENY,
}

sealed interface ExternalLinkResult {
    data object LoadHere : ExternalLinkResult
    data object OpenInSystem : ExternalLinkResult
    data object OpenIncognito : ExternalLinkResult
    data object Share : ExternalLinkResult
    data object CopyLink : ExternalLinkResult
    data object Dismissed : ExternalLinkResult
    data class OpenInPeelApp(val launcher: () -> Unit) : ExternalLinkResult
}

interface SessionHost {
    val effectiveSettings: WebAppSettings
    val baseUrl: String
    val policyOrigin: String
    val webAppName: String
    var canGoBack: Boolean
    var lastLoadedUrl: String

    var currentlyReloading: Boolean
    val hostProgressBar: ProgressBar?
    var hostOrientation: Int
    val hostWindow: Window

    var filePathCallback: ((Array<Uri>?) -> Unit)?
    var pendingCaptureFile: File?

    fun onLocationChanged(url: String)
    fun onPageStarted()
    fun onPageFullyLoaded()
    fun onPageLoadEnded()
    fun onFirstContentfulPaint()
    fun onSessionStateUpdated(state: GeckoSession.SessionState)
    fun onProcessKilled()
    fun onContentCrashed()
    fun openPopupSession(): GeckoResult<GeckoSession>

    fun showHttpAuthDialog(
        onResult: (username: String, password: String) -> Unit,
        onCancel: () -> Unit,
        url: String?,
    ): AlertDialog

    fun showTextPromptDialog(
        title: String?,
        message: String?,
        defaultValue: String?,
        onResult: (String) -> Unit,
        onCancel: () -> Unit,
    ): AlertDialog

    fun loadURL(url: String)
    fun goBackOrFinish()
    fun onWindowCloseRequest()
    fun onInitialNavigationDenied()
    fun markCurrentPageAsJumpHost()
    fun dismissRedirectToFallback(fallback: String)
    fun showConnectionError(description: String, url: String, onRetry: (() -> Unit)? = null)
    fun updateSystemBarColors(top: Int, bottom: Int)
    fun resetSystemBarColorsForNewPage()
    fun restoreSystemBarColors()
    fun reportSystemBarColorsFromContent(
        topCandidates: List<Int>,
        bottomCandidates: List<Int>,
        metaThemeColor: Int?,
    )

    fun findPeelAppMatches(url: String): List<WebApp>
    fun showExternalLinkMenu(
        url: String,
        onResult: (ExternalLinkResult) -> Unit,
    )

    fun startExternalIntent(uri: Uri)
    fun openIncognito(url: String)
    fun shareUrl(url: String)
    fun copyLink(url: String)
    fun showPermissionDialog(
        message: CharSequence,
        allowRemember: Boolean = false,
        onShown: (() -> Unit)? = null,
        onResult: (result: PermissionResult, remember: Boolean) -> Unit,
    )

    fun showDateTimePicker(
        request: DateTimePickerRequest,
        onResult: (String) -> Unit,
        onCancel: () -> Unit,
    ): DateTimePickerSession

    val themeBackgroundColor: Int

    fun runOnUi(action: Runnable)
    fun launchFilePicker(intent: Intent?): Boolean
    fun onWebFullscreenEnter()
    fun onWebFullscreenExit()
    fun requestOsPermissions(permissions: Array<String>, onResult: (granted: Boolean) -> Unit)
    fun hasPermissions(vararg permissions: String): Boolean

    val hostResources: Resources
}
