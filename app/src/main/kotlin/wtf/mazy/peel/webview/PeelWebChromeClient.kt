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

class PeelWebChromeClient(private val host: ChromeClientHost) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalOrientation = 0
    private var originalSystemUiVisibility = 0
    private val pageGranted = mutableSetOf<String>()
    private val pageDenied = mutableSetOf<String>()

    fun clearPagePermissions() {
        pageGranted.clear()
        pageDenied.clear()
    }

    companion object {
        private const val GEOLOCATION_KEY = "geolocation"
        private const val MAX_NAME_LENGTH = 30
    }

    private val trimmedName: String
        get() = host.webAppName.take(MAX_NAME_LENGTH)

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
            host.showToast(host.getString(R.string.no_filemanager))
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
        (hostWindow.decorView as FrameLayout).addView(customView, FrameLayout.LayoutParams(-1, -1))
        host.hideSystemBars()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val resources = request.resources.toList()
        val granted = mutableListOf<String>()
        val pending = mutableListOf<PendingPermission>()

        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
            if (host.effectiveSettings.isDrmAllowed == true) {
                granted.add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            }
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) {
            handleTriState(
                host.effectiveSettings.isCameraPermission,
                listOf(Manifest.permission.CAMERA),
                PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                R.string.permission_prompt_camera,
                pending,
            )
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) {
            handleTriState(
                host.effectiveSettings.isMicrophonePermission,
                listOf(
                    Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS
                ),
                PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                R.string.permission_prompt_microphone,
                pending,
            )
        }

        if (pending.isEmpty()) {
            finalizePermissionRequest(request, granted)
        } else {
            resolvePermissions(request, granted, pending, 0)
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
                when (GEOLOCATION_KEY) {
                    in pageDenied ->
                        callback?.invoke(origin, false, false)

                    in pageGranted ->
                        grantLocation(origin, callback)

                    else -> {
                        host.showPermissionDialog(
                            host.getString(R.string.permission_prompt_location, trimmedName)
                        ) { result ->
                            when (result) {
                                PermissionResult.ALLOW -> {
                                    pageGranted.add(GEOLOCATION_KEY)
                                    grantLocation(origin, callback)
                                }

                                PermissionResult.DENY -> {
                                    pageDenied.add(GEOLOCATION_KEY)
                                    callback?.invoke(origin, false, false)
                                }
                            }
                        }
                    }
                }
            }

            else -> callback?.invoke(origin, false, false)
        }
    }

    private fun grantLocation(origin: String?, callback: GeolocationPermissions.Callback?) {
        val permissions =
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ensureOsPermission(permissions) { granted ->
            callback?.invoke(origin, granted, false)
        }
    }

    private data class PendingPermission(
        val androidPermissions: List<String>,
        val webkitPermission: String,
        val promptResId: Int,
        val skipInAppDialog: Boolean,
    )

    private fun handleTriState(
        state: Int?,
        androidPermissions: List<String>,
        webkitPermission: String,
        promptResId: Int,
        pending: MutableList<PendingPermission>,
    ) {
        when {
            webkitPermission in pageDenied -> {}
            state == WebAppSettings.PERMISSION_ON || webkitPermission in pageGranted ->
                pending.add(
                    PendingPermission(androidPermissions, webkitPermission, promptResId, skipInAppDialog = true)
                )

            state == WebAppSettings.PERMISSION_ASK ->
                pending.add(
                    PendingPermission(androidPermissions, webkitPermission, promptResId, skipInAppDialog = false)
                )
        }
    }

    private fun finalizePermissionRequest(request: PermissionRequest, granted: List<String>) {
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun resolvePermissions(
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

        if (p.skipInAppDialog) {
            // PERMISSION_ON / already granted on this page: just ensure OS permission
            ensureOsPermission(p.androidPermissions) { osGranted ->
                if (osGranted) granted.add(p.webkitPermission)
                resolvePermissions(request, granted, pending, index + 1)
            }
            return
        }

        // PERMISSION_ASK: ensure OS permission first, then show in-app dialog
        ensureOsPermission(p.androidPermissions) { osGranted ->
            if (!osGranted) {
                resolvePermissions(request, granted, pending, index + 1)
                return@ensureOsPermission
            }
            showInAppDialog(p, granted) {
                resolvePermissions(request, granted, pending, index + 1)
            }
        }
    }

    private fun ensureOsPermission(
        androidPermissions: List<String>,
        onDone: (granted: Boolean) -> Unit,
    ) {
        val perms = androidPermissions.toTypedArray()
        if (host.hasPermissions(*perms)) {
            onDone(true)
        } else {
            host.requestOsPermissions(perms, onDone)
        }
    }

    private fun showInAppDialog(
        p: PendingPermission,
        granted: MutableList<String>,
        onDone: () -> Unit,
    ) {
        host.showPermissionDialog(host.getString(p.promptResId, trimmedName)) { result ->
            when (result) {
                PermissionResult.ALLOW -> {
                    pageGranted.add(p.webkitPermission)
                    granted.add(p.webkitPermission)
                }
                PermissionResult.DENY -> pageDenied.add(p.webkitPermission)
            }
            onDone()
        }
    }
}
