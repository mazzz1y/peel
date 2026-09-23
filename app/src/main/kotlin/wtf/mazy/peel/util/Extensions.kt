package wtf.mazy.peel.util

import android.app.Activity
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.URLUtil
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import wtf.mazy.peel.R
import java.net.URLDecoder
import java.text.BreakIterator
import kotlin.system.exitProcess

@Suppress("DEPRECATION")
fun Activity.disableSystemBarContrastEnforcement() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
}

const val STALE_UPLOAD_FILE_MAX_AGE_MS = 24L * 60 * 60 * 1000

fun java.io.File.deleteFilesOlderThan(maxAgeMs: Long = STALE_UPLOAD_FILE_MAX_AGE_MS) {
    val cutoff = System.currentTimeMillis() - maxAgeMs
    listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
}

fun prettyBaseUrl(url: String): String {
    val queryStart = url.indexOf('?')
    val clean = if (queryStart >= 0) url.substring(0, queryStart) else url
    return clean.trimEnd('/')
}

fun prettyHostLabel(url: String): String =
    prettyBaseUrl(url)
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")


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

fun Context.isDefaultBrowser(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = getSystemService<RoleManager>() ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
    }
    val probe = Intent(Intent.ACTION_VIEW, "http://example.com".toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val resolved = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.packageName == packageName
}

fun Context.shouldOfferOpenInSystem(url: String): Boolean {
    val isWebLink = url.startsWith("http://") || url.startsWith("https://")
    return !(isWebLink && isDefaultBrowser())
}

fun Context.shareText(text: String, title: String? = null) {
    startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title ?: text)
            },
            null,
        ),
    )
}

fun Context.copyToClipboard(text: String, toastResId: Int = R.string.link_copied) {
    val clipboard = getSystemService<ClipboardManager>() ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    toast(toastResId)
}

fun restartApp(activity: Activity) {
    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
    val componentName = intent.component ?: return
    activity.startActivity(Intent.makeRestartActivityTask(componentName))
    exitProcess(0)
}

private val unsafeFileNameChars = Regex("""[/\\:*?"<>|\x00-\x1F]""")
private val encodedFileNameParam = Regex("""filename\*=UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE)
private val plainFileNameParam = Regex("""filename="?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
private const val MAX_FILE_NAME_LENGTH = 64

fun downloadFileName(url: String?, contentDisposition: String?, mimeType: String?): String? {
    val fromDisposition = contentDisposition?.takeIf { it.isNotEmpty() }?.let { header ->
        encodedFileNameParam.find(header)?.groupValues?.get(1)?.let { encoded ->
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        } ?: plainFileNameParam.find(header)?.groupValues?.get(1)?.trim()
    }
    val fileName = fromDisposition
        ?: url?.let { URLUtil.guessFileName(it, contentDisposition, mimeType) }
    return fileName?.let(::sanitizeFileName)
}

private fun sanitizeFileName(name: String): String {
    val withoutPath = name.substringAfterLast('/').substringAfterLast('\\')
    val scrubbed = withoutPath.replace(unsafeFileNameChars, "_").trim().trim('.')
    return truncateFileName(scrubbed.ifBlank { "download" })
}

private fun truncateFileName(name: String): String {
    if (name.length <= MAX_FILE_NAME_LENGTH) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot in 1 until name.length) name.substring(dot) else ""
    val baseLimit = (MAX_FILE_NAME_LENGTH - ext.length).coerceAtLeast(1)
    return name.take(baseLimit) + ext
}
