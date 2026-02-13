package wtf.mazy.peel.webview

import android.content.Intent
import android.net.Uri
import android.view.Window
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.widget.ProgressBar
import wtf.mazy.peel.model.WebAppSettings

interface ChromeClientHost {
    val effectiveSettings: WebAppSettings
    var currentlyReloading: Boolean
    val hostProgressBar: ProgressBar?
    var hostOrientation: Int

    var filePathCallback: ValueCallback<Array<Uri?>?>?

    fun launchFilePicker(intent: Intent?): Boolean

    fun showSnackBar(message: String)

    val hostWindow: Window

    fun hideSystemBars()

    fun showSystemBars()

    fun requestHostPermissions(permissions: Array<String>, requestCode: Int)

    fun hasPermissions(vararg permissions: String): Boolean

    fun onGeoPermissionResult(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
        allow: Boolean
    )

    fun getString(resId: Int): String
}
