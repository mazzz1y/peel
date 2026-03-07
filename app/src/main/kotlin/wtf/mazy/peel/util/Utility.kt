package wtf.mazy.peel.util

import android.net.Uri
import android.webkit.URLUtil
import android.webkit.WebView
import java.net.URLDecoder
import java.text.BreakIterator
import java.util.regex.Pattern

private val CHROME_VERSION_RE = Regex("""Chrome/([\d.]+)""")
private val COMMON_SECOND_LEVEL_DOMAINS = setOf("ac", "co", "com", "edu", "gov", "mil", "net", "org")
private const val BRAND_MATCH_BONUS = 2

fun WebView.buildUserAgent() {
    val raw = settings.userAgentString ?: ""
    val major = CHROME_VERSION_RE.find(raw)?.groupValues?.get(1)?.substringBefore('.') ?: "145"
    settings.userAgentString =
        "Mozilla/5.0 (Linux; Android 10; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Mobile Safari/537.36"
}

fun displayUrl(url: String): String {
    val queryStart = url.indexOf('?')
    val clean = if (queryStart >= 0) url.substring(0, queryStart) else url
    return clean.trimEnd('/')
}

fun domainAffinity(appBaseUrl: String, targetUrl: String): Int {
    val appHost = Uri.parse(appBaseUrl).host?.removePrefix("www.") ?: return 0
    val targetHost = Uri.parse(targetUrl).host?.removePrefix("www.") ?: return 0
    val appParts = appHost.lowercase().split('.').reversed()
    val targetParts = targetHost.lowercase().split('.').reversed()
    var match = 0
    for (i in 0 until minOf(appParts.size, targetParts.size)) {
        if (appParts[i] == targetParts[i]) match++ else break
    }
    if (match > 0) return match
    val appBrand = registrableLabel(appHost) ?: return 0
    val targetBrand = registrableLabel(targetHost) ?: return 0
    return if (appBrand == targetBrand) BRAND_MATCH_BONUS else 0
}

private fun registrableLabel(host: String): String? {
    val parts = host.lowercase().split('.')
    if (parts.size < 2) return null
    val secondLevel = parts[parts.lastIndex - 1]
    val isCompoundTld = parts.size >= 3 && secondLevel in COMMON_SECOND_LEVEL_DOMAINS
    val labelIndex = if (isCompoundTld) parts.lastIndex - 2 else parts.lastIndex - 1
    return parts.getOrNull(labelIndex)
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

object Utility {

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

        return fileName
    }
}
