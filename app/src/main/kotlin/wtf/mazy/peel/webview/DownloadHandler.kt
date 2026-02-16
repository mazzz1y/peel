package wtf.mazy.peel.webview

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import wtf.mazy.peel.R
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.Utility.getFileNameFromDownload

class DownloadHandler(
    private val activity: AppCompatActivity,
    private val getWebView: () -> WebView?,
    private val getBaseUrl: () -> String,
    private val getProgressBar: () -> ProgressBar?,
    private val onDownloadComplete: () -> Unit,
) {
    private var pendingRequest: DownloadManager.Request? = null
    private var pendingDownloadUrl: String? = null

    fun install(webView: WebView) {
        webView.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(dlUrl, userAgent, contentDisposition, mimeType)
        }
    }

    fun onStoragePermissionGranted() {
        val request = pendingRequest ?: return
        val dlUrl = pendingDownloadUrl
        pendingRequest = null
        pendingDownloadUrl = null
        enqueueDownload(request)
        if (dlUrl != null) navigateBackAfterDownload(dlUrl)
    }

    private fun handleDownload(
        dlUrl: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (dlUrl.isNullOrEmpty()) return
        if (mimeType == "application/pdf" && contentDisposition.isNullOrEmpty()) return
        if (dlUrl.startsWith("blob:")) return

        val request: DownloadManager.Request
        try {
            request = DownloadManager.Request(dlUrl.toUri())
        } catch (_: Exception) {
            showToast(activity, activity.getString(R.string.file_download))
            return
        }

        val fileName = getFileNameFromDownload(dlUrl, contentDisposition, mimeType)
        request.setMimeType(mimeType)
        request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(dlUrl))
        request.addRequestHeader("User-Agent", userAgent)
        request.setTitle(fileName)
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val perms =
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                )
            if (!perms.all {
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            }) {
                pendingRequest = request
                pendingDownloadUrl = dlUrl
                ActivityCompat.requestPermissions(activity, perms, Const.PERMISSION_RC_STORAGE)
                return
            }
        }

        enqueueDownload(request)
        navigateBackAfterDownload(dlUrl)
    }

    private fun enqueueDownload(request: DownloadManager.Request) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        dm?.enqueue(request)
        showToast(activity, activity.getString(R.string.file_download))
    }

    private fun navigateBackAfterDownload(dlUrl: String) {
        val webView = getWebView() ?: return
        val currentUrl = webView.url
        if (currentUrl == null || currentUrl == dlUrl) {
            if (webView.canGoBack()) webView.goBack() else webView.loadUrl(getBaseUrl())
        }
        getProgressBar()?.visibility = ProgressBar.GONE
        Handler(Looper.getMainLooper()).postDelayed({ onDownloadComplete() }, 300)
    }
}
