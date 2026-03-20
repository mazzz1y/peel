package wtf.mazy.peel.webview

import android.net.Uri
import android.webkit.HttpAuthHandler
import wtf.mazy.peel.model.WebAppSettings

interface WebViewClientHost {
    val effectiveSettings: WebAppSettings
    val isDarkSchemeActive: Boolean
    val baseUrl: String
    val webappUuid: String?
    val navigationStartPoint: NavigationStartPoint

    fun onPageStarted()

    fun onPageFullyLoaded()

    fun showHttpAuthDialog(handler: HttpAuthHandler, host: String?, realm: String?)

    fun applyColorScheme()

    fun loadURL(url: String)

    fun finishActivity()

    fun showToast(message: String)

    fun showConnectionError(description: String, url: String)

    fun updateStatusBarColor(color: Int)

    fun startExternalIntent(uri: Uri)

    fun showPermissionDialog(message: CharSequence, onResult: (PermissionResult) -> Unit)

    val themeBackgroundColor: Int

    fun runOnUi(action: Runnable)

    fun getString(resId: Int): String

    fun getString(resId: Int, vararg formatArgs: Any): String
}
