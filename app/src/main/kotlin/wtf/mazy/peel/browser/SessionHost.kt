package wtf.mazy.peel.browser

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.dialog.DateTimePickerRequest
import wtf.mazy.peel.ui.dialog.DateTimePickerSession
import wtf.mazy.peel.ui.dialog.ExternalLinkResult
import java.io.File

enum class PermissionResult {
    ALLOW,
    DENY,
}

interface SessionHost {
    val effectiveSettings: EffectiveSettings
    val baseUrl: String
    val policyOrigin: String
    val webAppName: String

    /** The web app this host is showing, transient or stored; null for hosts with none. */
    val webAppUuid: String? get() = null

    /** The stored web app this host may write settings to, or null when it has none to write. */
    val persistableWebAppUuid: String? get() = null

    /** Re-reads settings the host had cached, after they were changed underneath it. */
    fun reloadEffectiveSettings() = Unit

    var canGoBack: Boolean
    var lastLoadedUrl: String

    var currentlyReloading: Boolean
    val hostProgressBar: ProgressBar?
    val hostContext: Context
    val hostScope: CoroutineScope

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
    fun resetSystemBarColorsForNewPage()
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
        allowRememberAlways: Boolean = false,
        onShown: (() -> Unit)? = null,
        onResult: (result: PermissionResult, remember: Boolean, always: Boolean) -> Unit,
    )

    fun showDateTimePicker(
        request: DateTimePickerRequest,
        onResult: (String) -> Unit,
        onCancel: () -> Unit,
    ): DateTimePickerSession

    fun runOnUi(action: () -> Unit)
    fun launchFilePicker(intent: Intent?): Boolean
    fun onWebFullscreenEnter()
    fun onWebFullscreenExit()

    /** Returns false when the host refuses to rotate, so callers can report that to content. */
    fun applyWebOrientation(orientation: Int): Boolean
    fun requestOsPermissions(permissions: Array<String>, onResult: (granted: Boolean) -> Unit)
    fun hasPermissions(vararg permissions: String): Boolean

    val hostResources: Resources
}
