package wtf.mazy.peel.webview

import android.content.Intent
import android.net.Uri
import android.view.Window
import android.webkit.ValueCallback
import android.widget.ProgressBar
import wtf.mazy.peel.model.WebAppSettings

enum class PermissionResult {
    ALLOW,
    DENY,
}

interface ChromeClientHost {
    val webAppName: String
    val effectiveSettings: WebAppSettings
    var currentlyReloading: Boolean
    val hostProgressBar: ProgressBar?
    var hostOrientation: Int

    var filePathCallback: ValueCallback<Array<Uri?>?>?

    fun launchFilePicker(intent: Intent?): Boolean

    fun showToast(message: String)

    val hostWindow: Window

    fun hideSystemBars()

    fun showSystemBars()

    fun requestOsPermissions(permissions: Array<String>, onResult: (granted: Boolean) -> Unit)

    fun hasPermissions(vararg permissions: String): Boolean

    fun showPermissionDialog(message: String, onResult: (PermissionResult) -> Unit)

    fun getString(resId: Int): String

    fun getString(resId: Int, vararg formatArgs: Any): String

    fun onPageFullyLoaded()
}
