package wtf.mazy.peel.browser

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.view.Window
import android.widget.ProgressBar
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings

enum class PermissionResult {
    ALLOW,
    DENY,
}

sealed interface ExternalLinkResult {
    data object LoadHere : ExternalLinkResult
    data object OpenInSystem : ExternalLinkResult
    data object Dismissed : ExternalLinkResult
    data class OpenInPeelApp(val launcher: () -> Unit) : ExternalLinkResult
}

interface SessionHost {
    val effectiveSettings: WebAppSettings
    val baseUrl: String
    val webAppName: String
    var canGoBack: Boolean
    var lastLoadedUrl: String

    var currentlyReloading: Boolean
    val hostProgressBar: ProgressBar?
    var hostOrientation: Int
    val hostWindow: Window

    var filePathCallback: ((Array<Uri>?) -> Unit)?

    fun onLocationChanged(url: String)
    fun onPageStarted()
    fun onPageFullyLoaded()
    fun onFirstContentfulPaint()

    fun showHttpAuthDialog(
        onResult: (username: String, password: String) -> Unit,
        onCancel: () -> Unit,
        url: String?,
    )

    fun loadURL(url: String)
    fun goBackOrFinish()
    fun dismissRedirectToFallback(fallback: String)
    fun showConnectionError(description: String, url: String)
    fun updateStatusBarColor(color: Int)
    fun findPeelAppMatches(url: String): List<WebApp>
    fun showExternalLinkMenu(
        url: String,
        onResult: (ExternalLinkResult) -> Unit,
    )

    fun startExternalIntent(uri: Uri)
    fun showPermissionDialog(message: CharSequence, onResult: (PermissionResult) -> Unit)

    val themeBackgroundColor: Int

    fun runOnUi(action: Runnable)
    fun launchFilePicker(intent: Intent?): Boolean
    fun onWebFullscreenEnter()
    fun onWebFullscreenExit()
    fun requestOsPermissions(permissions: Array<String>, onResult: (granted: Boolean) -> Unit)
    fun hasPermissions(vararg permissions: String): Boolean

    val hostResources: Resources
}
