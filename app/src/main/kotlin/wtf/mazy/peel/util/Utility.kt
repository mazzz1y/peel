package wtf.mazy.peel.util

import android.app.Activity
import android.content.Intent
import android.webkit.URLUtil
import java.net.URLDecoder
import java.text.BreakIterator
import java.util.regex.Pattern
import kotlin.system.exitProcess

fun displayUrl(url: String): String {
    val queryStart = url.indexOf('?')
    val clean = if (queryStart >= 0) url.substring(0, queryStart) else url
    return clean.trimEnd('/')
}


fun shortLabel(title: String): String =
    leadingEmojis(title, 3) ?: title

fun leadingEmojis(text: String, max: Int): String? {
    val iter = BreakIterator.getCharacterInstance()
    iter.setText(text)
    var prev = 0
    var count = 0
    while (count < max) {
        val end = iter.next()
        if (end == BreakIterator.DONE) break
        val segment = text.substring(prev, end)
        if (segment.isBlank() || Character.isLetterOrDigit(segment.codePointAt(0))) break
        prev = end
        count++
    }
    return if (count > 0) text.substring(0, prev) else null
}

fun restartApp(activity: Activity) {
    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
    val componentName = intent.component ?: return
    activity.startActivity(Intent.makeRestartActivityTask(componentName))
    exitProcess(0)
}

object Utility {

    private val unsafeFileNameChars = Regex("""[/\\:*?"<>|\x00-\x1F]""")

    @JvmStatic
    fun getFileNameFromDownload(
        url: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): String? {
        var fileName: String? = null
        if (contentDisposition != null && contentDisposition != "") {
            var pattern = Pattern.compile("filename\\*=UTF-8''([^;\\s]+)", Pattern.CASE_INSENSITIVE)
            var m = pattern.matcher(contentDisposition)
            if (m.find()) {
                fileName =
                    try {
                        URLDecoder.decode(m.group(1), "UTF-8")
                    } catch (_: Exception) {
                        m.group(1)
                    }
            } else {
                pattern = Pattern.compile("filename=\"?([^\";\r\n]+)\"?", Pattern.CASE_INSENSITIVE)
                m = pattern.matcher(contentDisposition)
                if (m.find()) {
                    fileName = m.group(1)?.trim { it <= ' ' }
                }
            }
        }
        if (fileName == null) {
            fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        }

        return fileName?.let { sanitizeFileName(it) }
    }

    private fun sanitizeFileName(name: String): String {
        val withoutPath = name.substringAfterLast('/').substringAfterLast('\\')
        val scrubbed = withoutPath.replace(unsafeFileNameChars, "_").trim().trim('.')
        return scrubbed.ifBlank { "download" }
    }
}
