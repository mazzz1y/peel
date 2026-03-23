package wtf.mazy.peel.webview

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import wtf.mazy.peel.R
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.Utility.getFileNameFromDownload
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

class DownloadHandler(
    private val activity: AppCompatActivity,
    private val getRuntime: () -> GeckoRuntime,
) {
    private var promptShowing = false

    fun onExternalResponse(response: WebResponse) {
        Log.d(TAG, "onExternalResponse: uri=${response.uri} headers=${response.headers}")
        val contentDisposition = response.headers["Content-Disposition"]
        val contentType = response.headers["Content-Type"]?.substringBefore(";")?.trim()
        val fileName = resolveFileName(response.uri, contentDisposition, contentType)

        showDownloadPrompt(fileName) { allowed ->
            promptShowing = false
            if (!allowed) {
                response.body?.close()
                return@showDownloadPrompt
            }
            val body = response.body
            if (body == null) {
                showToast(activity, activity.getString(R.string.download_failed))
                return@showDownloadPrompt
            }
            thread {
                val ok = saveToDownloads(body, fileName, contentType)
                activity.runOnUiThread {
                    showToast(
                        activity,
                        activity.getString(if (ok) R.string.file_download else R.string.download_failed),
                    )
                }
            }
        }
    }

    fun downloadUrl(url: String) {
        if (url.startsWith("data:")) {
            val parsed = parseDataUri(url) ?: run {
                showToast(activity, activity.getString(R.string.download_failed))
                return
            }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(parsed.mime)
            val fileName = if (ext != null) "download.$ext" else "download"
            showDownloadPrompt(fileName) { allowed ->
                promptShowing = false
                if (!allowed) return@showDownloadPrompt
                thread {
                    val ok = saveToDownloads(ByteArrayInputStream(parsed.bytes), fileName, parsed.mime)
                    activity.runOnUiThread {
                        showToast(
                            activity,
                            activity.getString(if (ok) R.string.file_download else R.string.download_failed),
                        )
                    }
                }
            }
            return
        }
        val executor = GeckoWebExecutor(getRuntime())
        executor.fetch(WebRequest(url)).then({ response ->
            val resp = response ?: run {
                activity.runOnUiThread { showToast(activity, activity.getString(R.string.download_failed)) }
                return@then GeckoResult<Void>()
            }
            val contentType = resp.headers["Content-Type"]?.substringBefore(";")?.trim()
            val disposition = resp.headers["Content-Disposition"]
            val fileName = resolveFileName(resp.uri, disposition, contentType)
            val body = resp.body
            activity.runOnUiThread {
                if (body == null) {
                    showToast(activity, activity.getString(R.string.download_failed))
                } else {
                    showDownloadPrompt(fileName) { allowed ->
                        promptShowing = false
                        if (!allowed) { body.close(); return@showDownloadPrompt }
                        thread {
                            val ok = saveToDownloads(body, fileName, contentType)
                            activity.runOnUiThread {
                                showToast(
                                    activity,
                                    activity.getString(if (ok) R.string.file_download else R.string.download_failed),
                                )
                            }
                        }
                    }
                }
            }
            GeckoResult<Void>()
        }, { _ ->
            activity.runOnUiThread {
                showToast(activity, activity.getString(R.string.download_failed))
            }
            GeckoResult<Void>()
        })
    }

    fun shareImage(url: String) {
        if (url.startsWith("data:")) {
            val parsed = parseDataUri(url) ?: return
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(parsed.mime)
            val fileName = if (ext != null) "image.$ext" else "image"
            thread {
                try {
                    val shareDir = File(activity.cacheDir, "shared_files").apply { mkdirs() }
                    val file = File(shareDir, fileName)
                    file.writeBytes(parsed.bytes)
                    launchShareIntent(file, parsed.mime ?: "image/*", fileName)
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        showToast(activity, activity.getString(R.string.download_failed))
                    }
                }
            }
            return
        }
        val executor = GeckoWebExecutor(getRuntime())
        executor.fetch(WebRequest(url)).then({ response ->
            val resp = response ?: return@then GeckoResult<Void>()
            val contentType = resp.headers["Content-Type"]?.substringBefore(";")?.trim()
            val fileName = resolveFileName(resp.uri, resp.headers["Content-Disposition"], contentType)
            val body = resp.body ?: return@then GeckoResult<Void>()
            thread {
                try {
                    val shareDir = File(activity.cacheDir, "shared_files").apply { mkdirs() }
                    val file = File(shareDir, fileName)
                    file.outputStream().use { out -> body.use { it.copyTo(out) } }
                    val mime = contentType ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
                        ?: "image/*"
                    launchShareIntent(file, mime, fileName)
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        showToast(activity, activity.getString(R.string.download_failed))
                    }
                }
            }
            GeckoResult<Void>()
        }, { _ ->
            GeckoResult<Void>()
        })
    }

    private fun launchShareIntent(file: File, mime: String, fileName: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(activity.contentResolver, fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, null))
        }
    }

    private data class DataUriPayload(val mime: String?, val bytes: ByteArray)

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

    companion object {
        private const val TAG = "PeelDownload"
    }

    private fun resolveFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        if (url.startsWith("blob:") || url.startsWith("data:")) return "download"
        return getFileNameFromDownload(url, contentDisposition, mimeType) ?: "download"
    }

    private fun showDownloadPrompt(fileName: String, onResult: (Boolean) -> Unit) {
        if (promptShowing) return
        promptShowing = true
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

    private fun saveToDownloads(input: InputStream, fileName: String, mimeType: String?): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(input, fileName, mimeType)
        } else {
            saveToLegacy(input, fileName)
        }
    }

    private fun saveToMediaStore(input: InputStream, fileName: String, mimeType: String?): Boolean {
        val mime = mimeType
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
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
            out.use { o -> input.use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun saveToLegacy(input: InputStream, fileName: String): Boolean {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = uniqueFile(dir, fileName)
            target.outputStream().use { out -> input.use { it.copyTo(out) } }
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
}
