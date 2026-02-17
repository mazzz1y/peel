package wtf.mazy.peel.webview

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.graphics.createBitmap
import androidx.core.view.isGone
import wtf.mazy.peel.R
import wtf.mazy.peel.util.Const

class PeelWebChromeClient(
    private val host: ChromeClientHost,
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalOrientation = 0
    private var originalSystemUiVisibility = 0

    override fun onShowFileChooser(
        webView: WebView?,
        callback: ValueCallback<Array<Uri?>?>?,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        host.filePathCallback = callback
        try {
            if (!host.launchFilePicker(fileChooserParams.createIntent())) {
                host.filePathCallback = null
                callback?.onReceiveValue(null)
                return true
            }
        } catch (_: Exception) {
            host.filePathCallback = null
            callback?.onReceiveValue(null)
            host.showSnackBar(host.getString(R.string.no_filemanager))
        }
        return true
    }

    override fun getDefaultVideoPoster(): Bitmap {
        val bitmap = createBitmap(1, 1)
        Canvas(bitmap).drawARGB(0, 0, 0, 0)
        return bitmap
    }

    override fun onHideCustomView() {
        val hostWindow = host.hostWindow
        (hostWindow.decorView as FrameLayout).removeView(customView)
        customView = null
        @Suppress("DEPRECATION")
        hostWindow.decorView.systemUiVisibility = originalSystemUiVisibility
        host.hostOrientation = originalOrientation
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        host.showSystemBars()
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            onHideCustomView()
            return
        }
        customView = view
        val hostWindow = host.hostWindow
        @Suppress("DEPRECATION")
        originalSystemUiVisibility = hostWindow.decorView.systemUiVisibility
        originalOrientation = host.hostOrientation
        customViewCallback = callback
        (hostWindow.decorView as FrameLayout).addView(
            customView,
            FrameLayout.LayoutParams(-1, -1),
        )
        host.hideSystemBars()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val permissionsToGrant = mutableListOf<String>()
        val resources = request.resources.toList()

        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
            if (host.effectiveSettings.isDrmAllowed == true) {
                permissionsToGrant.add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            }
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) {
            grantIfAllowed(
                host.effectiveSettings.isCameraPermission == true,
                arrayOf(Manifest.permission.CAMERA),
                Const.PERMISSION_CAMERA,
                permissionsToGrant,
                PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            )
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) {
            grantIfAllowed(
                host.effectiveSettings.isMicrophonePermission == true,
                arrayOf(
                    Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS),
                Const.PERMISSION_AUDIO,
                permissionsToGrant,
                PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            )
        }

        request.grant(permissionsToGrant.toTypedArray())
    }

    override fun onProgressChanged(view: WebView?, progress: Int) {
        if (host.effectiveSettings.isShowProgressbar == true || host.currentlyReloading) {
            host.hostProgressBar?.let { bar ->
                if (bar.isGone && progress < 100) bar.visibility = ProgressBar.VISIBLE
                bar.progress = progress
                if (progress == 100) {
                    bar.visibility = ProgressBar.GONE
                    host.currentlyReloading = false
                }
            }
        }
        if (progress == 100) {
            host.onPageFullyLoaded()
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        if (host.effectiveSettings.isAllowLocationAccess != true) {
            callback?.invoke(origin, false, false)
            return
        }
        val permissions =
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        if (!host.hasPermissions(*permissions)) {
            host.onGeoPermissionResult(origin, callback, false)
            host.requestHostPermissions(permissions, Const.PERMISSION_RC_LOCATION)
        } else {
            callback?.invoke(origin, true, false)
        }
    }

    private fun grantIfAllowed(
        isEnabled: Boolean,
        androidPermissions: Array<String>,
        requestCode: Int,
        permissionsToGrant: MutableList<String>,
        webkitPermission: String,
    ) {
        if (!isEnabled) return
        if (!host.hasPermissions(*androidPermissions)) {
            host.requestHostPermissions(androidPermissions, requestCode)
        } else {
            permissionsToGrant.add(webkitPermission)
        }
    }
}
