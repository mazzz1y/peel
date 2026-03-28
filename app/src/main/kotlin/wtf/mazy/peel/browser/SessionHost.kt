package wtf.mazy.peel.browser

import android.content.Intent
import android.net.Uri
import android.view.Window
import android.widget.ProgressBar
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings

enum class PermissionResult {
    ALLOW,
    DENY,
}

interface SessionHost {
    val effectiveSettings: WebAppSettings
    val isDarkSchemeActive: Boolean
    val baseUrl: String
    val webappUuid: String?
    val webAppName: String
    var canGoBack: Boolean
    var currentUrl: String

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
        host: String?,
        realm: String?,
    )

    fun loadURL(url: String)
    fun showConnectionError(description: String, url: String)
    fun updateStatusBarColor(color: Int)
    fun findPeelAppMatches(url: String): List<WebApp>
    fun showPeelAppRoutingDialog(matches: List<WebApp>, url: String, onDismiss: () -> Unit)
    fun startExternalIntent(uri: Uri)
    fun showPermissionDialog(message: CharSequence, onResult: (PermissionResult) -> Unit)

    val themeBackgroundColor: Int

    fun runOnUi(action: Runnable)
    fun launchFilePicker(intent: Intent?): Boolean
    fun hideSystemBars()
    fun showSystemBars()
    fun requestOsPermissions(permissions: Array<String>, onResult: (granted: Boolean) -> Unit)
    fun hasPermissions(vararg permissions: String): Boolean

    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String
}
