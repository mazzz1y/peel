package wtf.mazy.peel.browser

import android.content.ClipData
import android.content.Intent
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import wtf.mazy.peel.R
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.Utility.getFileNameFromDownload
import wtf.mazy.peel.util.withMonoSpan
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class DownloadHandler(
    private val activity: AppCompatActivity,
    private val getRuntime: () -> GeckoRuntime,
    private val scope: CoroutineScope,
    private val webappName: String,
) {
    private var promptShowing = false

    fun onExternalResponse(response: WebResponse) {
        val contentType = extractMimeType(response.headers)
        val contentLength = extractContentLength(response.headers)
        val fileName =
            resolveFileName(response.uri, response.headers["Content-Disposition"], contentType)
        promptAndSave(fileName, contentType, contentLength, response.body)
    }

    fun downloadUrl(url: String) {
        if (url.startsWith("data:")) {
            val parsed = parseDataUri(url) ?: run { showError(); return }
            val fileName = resolveFileName(url, null, parsed.mime)
            promptAndSave(
                fileName,
                parsed.mime,
                parsed.bytes.size.toLong(),
                ByteArrayInputStream(parsed.bytes)
            )
            return
        }
        fetchUrl(url, onError = { showError() }) { resp ->
            val contentType = extractMimeType(resp.headers)
            val contentLength = extractContentLength(resp.headers)
            val fileName =
                resolveFileName(resp.uri, resp.headers["Content-Disposition"], contentType)
            promptAndSave(fileName, contentType, contentLength, resp.body)
        }
    }

    fun shareImage(url: String) {
        if (url.startsWith("data:")) {
            val parsed = parseDataUri(url) ?: return
            val fileName = resolveFileName(url, null, parsed.mime)
            writeAndShare(ByteArrayInputStream(parsed.bytes), fileName, parsed.mime ?: "image/*")
            return
        }
        fetchUrl(url) { resp ->
            val contentType = extractMimeType(resp.headers)
            val fileName =
                resolveFileName(resp.uri, resp.headers["Content-Disposition"], contentType)
            val body = resp.body ?: return@fetchUrl
            val mime = contentType
                ?: extensionToMime(fileName.substringAfterLast('.', ""))
                ?: "image/*"
            writeAndShare(body, fileName, mime)
        }
    }

    private fun fetchUrl(
        url: String,
        onError: (() -> Unit)? = null,
        onResponse: (WebResponse) -> Unit,
    ) {
        val executor = GeckoWebExecutor(getRuntime())
        executor.fetch(WebRequest(url)).then({ response ->
            val resp = response ?: run {
                onError?.let { activity.runOnUiThread { it() } }
                return@then GeckoResult<Void>()
            }
            activity.runOnUiThread { onResponse(resp) }
            GeckoResult()
        }, { _ ->
            onError?.let { activity.runOnUiThread { it() } }
            GeckoResult()
        })
    }

    private fun promptAndSave(
        fileName: String, mimeType: String?, contentLength: Long, body: InputStream?,
    ) {
        if (body == null) {
            showError(); return
        }
        showDownloadPrompt(fileName) { allowed ->
            if (!allowed) {
                body.close()
                return@showDownloadPrompt
            }
            DownloadService.start(activity, fileName, mimeType, contentLength, webappName, body)
        }
    }

    private fun writeAndShare(body: InputStream, fileName: String, mime: String) {
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val shareDir = File(activity.cacheDir, "shared_files").apply { mkdirs() }
                    val f = File(shareDir, fileName)
                    f.outputStream().use { out -> body.use { it.copyTo(out) } }
                    f
                }
                launchShareIntent(file, mime, fileName)
            } catch (_: Exception) {
                showError()
            }
        }
    }

    private fun launchShareIntent(file: File, mime: String, fileName: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(activity.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, null))
    }

    private fun showDownloadPrompt(fileName: String, onResult: (Boolean) -> Unit) {
        if (promptShowing) return
        promptShowing = true
        val message = activity.getString(R.string.permission_prompt_download, fileName)
            .withMonoSpan(fileName)
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_prompt_allow) { _, _ -> onResult(true) }
            .setNegativeButton(R.string.permission_prompt_deny) { _, _ -> onResult(false) }
            .setOnDismissListener { promptShowing = false }
            .show()
    }

    private fun showError() {
        showToast(activity, activity.getString(R.string.download_failed))
    }

    private fun resolveFileName(
        url: String, contentDisposition: String?, mimeType: String?,
    ): String {
        val name = getFileNameFromDownload(null, contentDisposition, mimeType)
            ?: when {
                url.startsWith("blob:") || url.startsWith("data:") -> "download"
                else -> getFileNameFromDownload(url, null, mimeType) ?: "download"
            }
        if ('.' in name || mimeType == null) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (ext != null) "$name.$ext" else name
    }

    private fun extractMimeType(headers: Map<String, String>): String? =
        headers["Content-Type"]?.substringBefore(";")?.trim()

    private fun extractContentLength(headers: Map<String, String>): Long =
        headers["Content-Length"]?.toLongOrNull() ?: -1L

    private fun extensionToMime(ext: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)

    private data class DataUriPayload(val mime: String?, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DataUriPayload

            if (mime != other.mime) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = mime?.hashCode() ?: 0
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    private fun parseDataUri(uri: String): DataUriPayload? {
        val header = uri.substringBefore(",")
        val data = uri.substringAfter(",", "")
        if (data.isEmpty()) return null
        val mime = header.removePrefix("data:").removeSuffix(";base64").takeIf { it.isNotBlank() }
        val bytes = if (header.endsWith(";base64")) {
            Base64.decode(data, Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(data, "UTF-8").toByteArray()
        }
        return DataUriPayload(mime, bytes)
    }
}
