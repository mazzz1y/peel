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
import wtf.mazy.peel.model.WebAppSettings
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
        val resources = request.resources.toList()
        val immediate = mutableListOf<String>()
        val pending = mutableListOf<PendingPermission>()

        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
            if (host.effectiveSettings.isDrmAllowed == true) {
                immediate.add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            }
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) {
            handleTriState(
                host.effectiveSettings.isCameraPermission,
                arrayOf(Manifest.permission.CAMERA),
                Const.PERMISSION_CAMERA,
                PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                R.string.permission_prompt_camera,
                immediate,
                pending,
            )
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) {
            handleTriState(
                host.effectiveSettings.isMicrophonePermission,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS),
                Const.PERMISSION_AUDIO,
                PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                R.string.permission_prompt_microphone,
                immediate,
                pending,
            )
        }

        if (pending.isEmpty()) {
            finalizePermissionRequest(request, immediate)
        } else {
            resolveAskPermissions(request, immediate, pending, 0)
        }
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
        val state = host.effectiveSettings.isAllowLocationAccess
        when (state) {
            WebAppSettings.PERMISSION_ON -> grantLocation(origin, callback)
            WebAppSettings.PERMISSION_ASK -> {
                host.showPermissionDialog(host.getString(R.string.permission_prompt_location)) { allowed ->
                    if (allowed) grantLocation(origin, callback)
                    else callback?.invoke(origin, false, false)
                }
            }
            else -> callback?.invoke(origin, false, false)
        }
    }

    private fun grantLocation(origin: String?, callback: GeolocationPermissions.Callback?) {
        val permissions = arrayOf(
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

    private data class PendingPermission(
        val androidPermissions: Array<String>,
        val requestCode: Int,
        val webkitPermission: String,
        val promptResId: Int,
    )

    private fun handleTriState(
        state: Int?,
        androidPermissions: Array<String>,
        requestCode: Int,
        webkitPermission: String,
        promptResId: Int,
        immediate: MutableList<String>,
        pending: MutableList<PendingPermission>,
    ) {
        when (state) {
            WebAppSettings.PERMISSION_ON -> {
                if (!host.hasPermissions(*androidPermissions)) {
                    host.requestHostPermissions(androidPermissions, requestCode)
                } else {
                    immediate.add(webkitPermission)
                }
            }
            WebAppSettings.PERMISSION_ASK -> {
                pending.add(PendingPermission(androidPermissions, requestCode, webkitPermission, promptResId))
            }
        }
    }

    private fun finalizePermissionRequest(request: PermissionRequest, granted: List<String>) {
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun resolveAskPermissions(
        request: PermissionRequest,
        granted: MutableList<String>,
        pending: List<PendingPermission>,
        index: Int,
    ) {
        if (index >= pending.size) {
            finalizePermissionRequest(request, granted)
            return
        }
        val p = pending[index]
        host.showPermissionDialog(host.getString(p.promptResId)) { allowed ->
            if (allowed) {
                if (!host.hasPermissions(*p.androidPermissions)) {
                    host.requestHostPermissions(p.androidPermissions, p.requestCode)
                } else {
                    granted.add(p.webkitPermission)
                }
            }
            resolveAskPermissions(request, granted, pending, index + 1)
        }
    }
}
