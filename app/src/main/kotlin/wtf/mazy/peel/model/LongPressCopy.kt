package wtf.mazy.peel.model

import java.net.URLDecoder

/** What a long press on a link that is not a web address puts on the clipboard. */
object LongPressCopy {

    private val FALLBACK = Regex(";S\\.browser_fallback_url=([^;]+)")

    fun of(uri: String): String {
        val scheme = uri.substringBefore(':', "").lowercase()
        val rest = uri.substringAfter(':', "")
        val copied = when (scheme) {
            "mailto", "tel", "sms", "smsto" -> decode(rest.substringBefore('?').removePrefix("//"))
            "intent" -> FALLBACK.find(uri)?.groupValues?.get(1)?.let(::decode)
            else -> null
        }
        return copied?.takeIf { it.isNotBlank() } ?: uri
    }

    private fun decode(text: String): String? =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrNull()
}
