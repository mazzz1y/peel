package wtf.mazy.peel.webview

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.Utility.getFileNameFromDownload
import java.io.File

class DownloadHandler(
    private val activity: AppCompatActivity,
    private val getWebView: () -> WebView?,
) {
    lateinit var fileFetcher: FileFetcher
    lateinit var getBaseUrl: () -> String

    private var pendingAction: (() -> Unit)? = null
    private var promptShowing = false

    private val storagePermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.isNotEmpty() && results.values.all { it }) {
                pendingAction?.invoke()
            }
            pendingAction = null
        }

    fun install(webView: WebView) {
        webView.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            handleListenerDownload(dlUrl, userAgent, contentDisposition, mimeType)
        }
    }

    fun downloadUrl(url: String) {
        val fileName = resolveFileName(url, null, null)
        promptAndDownload(url, fileName)
    }

    private fun handleListenerDownload(
        dlUrl: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (dlUrl.isNullOrEmpty()) return

        val fileName = resolveFileName(dlUrl, contentDisposition, mimeType)
        promptAndDownload(dlUrl, fileName, userAgent, mimeType)
    }

    private fun resolveFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        if (url.startsWith("blob:") || url.startsWith("data:")) return "download"
        return getFileNameFromDownload(url, contentDisposition, mimeType) ?: "download"
    }

    private fun promptAndDownload(
        url: String,
        fileName: String,
        userAgent: String? = null,
        mimeType: String? = null,
    ) {
        if (promptShowing) return
        promptShowing = true

        showDownloadPrompt(fileName) { allowed ->
            promptShowing = false
            if (!allowed) return@showDownloadPrompt

            if (url.startsWith("blob:") || url.startsWith("data:")) {
                downloadViaFetcher(url, fileName)
            } else {
                val ua = userAgent ?: getWebView()?.settings?.userAgentString ?: ""
                enqueueHttpDownload(url, ua, mimeType, fileName)
            }
        }
    }

    private fun showDownloadPrompt(fileName: String, onResult: (Boolean) -> Unit) {
        val text = activity.getString(R.string.permission_prompt_download, fileName)
        val nameStart = text.indexOf(fileName)
        val message: CharSequence = if (nameStart >= 0) {
            SpannableString(text).apply {
                setSpan(
                    TypefaceSpan("monospace"),
                    nameStart,
                    nameStart + fileName.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else {
            text
        }

        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_prompt_allow) { _, _ -> onResult(true) }
            .setNegativeButton(R.string.permission_prompt_deny) { _, _ -> onResult(false) }
            .setOnDismissListener { promptShowing = false }
            .show()
    }

    private fun downloadViaFetcher(url: String, fileName: String) {
        fileFetcher.fetch(url, fileName) { file ->
            if (file == null) {
                showToast(activity, activity.getString(R.string.download_failed))
                return@fetch
            }
            val target = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                file.name,
            )
            file.copyTo(target, overwrite = true)
            showToast(activity, activity.getString(R.string.file_download))
        }
    }

    private fun enqueueHttpDownload(
        url: String,
        userAgent: String,
        mimeType: String?,
        fileName: String,
    ) {
        val request = try {
            DownloadManager.Request(url.toUri())
        } catch (_: Exception) {
            showToast(activity, activity.getString(R.string.file_download))
            return
        }

        request.setMimeType(mimeType)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("cookie", it) }
        request.addRequestHeader("User-Agent", userAgent)
        request.setTitle(fileName)
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val perms = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )
            if (!perms.all {
                    ContextCompat.checkSelfPermission(
                        activity,
                        it
                    ) == PackageManager.PERMISSION_GRANTED
                }) {
                pendingAction = { enqueue(request, url) }
                storagePermissionLauncher.launch(perms)
                return
            }
        }

        enqueue(request, url)
    }

    private fun enqueue(request: DownloadManager.Request, url: String) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        dm?.enqueue(request)
        showToast(activity, activity.getString(R.string.file_download))
        navigateBackIfNeeded(url)
    }

    private fun navigateBackIfNeeded(url: String) {
        val webView = getWebView() ?: return
        val currentUrl = webView.url
        if (currentUrl == null || currentUrl == url) {
            if (webView.canGoBack()) webView.goBack() else webView.loadUrl(getBaseUrl())
        }
    }
}
