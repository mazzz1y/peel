package wtf.mazy.peel.webview

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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
    var getCustomHeaders: (() -> Map<String, String>)? = null

    private var pendingAction: (() -> Unit)? = null
    private var promptShowing = false
    private var fetching = false

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
        webView.addJavascriptInterface(fileFetcher.bridge, FileFetcher.BRIDGE_NAME)
    }

    fun onPageStarted(webView: WebView) {
        webView.evaluateJavascript(FileFetcher.BLOB_STORE_JS, null)
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

        if (url.startsWith("blob:") || url.startsWith("data:")) {
            fetchThenPrompt(url, fileName)
        } else {
            promptShowing = true
            showDownloadPrompt(fileName) { allowed ->
                promptShowing = false
                if (!allowed) return@showDownloadPrompt
                val ua = userAgent
                    ?: getWebView()?.settings?.userAgentString
                    ?: WebSettings.getDefaultUserAgent(activity)
                enqueueHttpDownload(url, ua, mimeType, fileName)
            }
        }
    }

    private fun fetchThenPrompt(url: String, fileName: String) {
        if (fetching) return
        fetching = true
        fileFetcher.fetch(url, fileName) { file ->
            if (file == null) {
                fetching = false
                val msgId = if (fileFetcher.bridge.lastError == FileFetcher.ERROR_TOO_LARGE)
                    R.string.download_too_large else R.string.download_failed
                showToast(activity, activity.getString(msgId))
                return@fetch
            }
            promptShowing = true
            showDownloadPrompt(file.name) { allowed ->
                promptShowing = false
                fetching = false
                if (!allowed) {
                    file.delete()
                    return@showDownloadPrompt
                }
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    copyToDownloadsMediaStore(file)
                } else {
                    copyToDownloadsLegacy(file)
                }
                file.delete()
                showToast(
                    activity,
                    activity.getString(if (ok) R.string.file_download else R.string.download_failed),
                )
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyToDownloadsMediaStore(file: File): Boolean {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension)
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                return false
            }
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun copyToDownloadsLegacy(file: File): Boolean {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = uniqueFile(dir, file.name)
            file.copyTo(target)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
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
            showToast(activity, activity.getString(R.string.download_failed))
            return
        }

        request.setMimeType(mimeType)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        request.addRequestHeader("User-Agent", userAgent)
        getWebView()?.url?.let { request.addRequestHeader("Referer", it) }
        getCustomHeaders?.invoke()?.forEach { (key, value) ->
            if (!key.equals("User-Agent", ignoreCase = true)) {
                request.addRequestHeader(key, value)
            }
        }
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
