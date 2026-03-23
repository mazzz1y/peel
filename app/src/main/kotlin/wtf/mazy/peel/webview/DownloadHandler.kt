package wtf.mazy.peel.webview

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import wtf.mazy.peel.R
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.Utility.getFileNameFromDownload
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

class DownloadHandler(
    private val activity: AppCompatActivity,
    private val getSession: () -> GeckoSession?,
    private val getBaseUrl: () -> String,
) {
    private var promptShowing = false

    fun onExternalResponse(response: WebResponse) {
        val contentDisposition = response.headers["Content-Disposition"]
        val contentType = response.headers["Content-Type"]?.substringBefore(";")?.trim()
        val fileName = resolveFileName(response.uri, contentDisposition, contentType)

        promptShowing = true
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
        val session = getSession() ?: return
        session.loadUri(url)
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
