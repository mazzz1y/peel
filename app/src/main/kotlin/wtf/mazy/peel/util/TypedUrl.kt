package wtf.mazy.peel.util

import androidx.core.net.toUri

/**
 * What the user typed read as a site. The one place that decides whether text is URL-shaped
 * and what scheme it gets, shared by the add action and the search suggestions.
 *
 * URL-shaped: an explicit web scheme, or no whitespace and a host — dotted, `localhost` or an
 * IP literal. A bare word is not a site; nothing is guessed on its behalf.
 */
class TypedUrl private constructor(val url: String, val host: String) {

    companion object {
        fun normalize(text: String): String {
            val trimmed = text.trim()
            return if (hasWebScheme(trimmed)) trimmed else "https://$trimmed"
        }

        fun parse(text: String): TypedUrl? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val explicit = hasWebScheme(trimmed)
            val url = normalize(trimmed)
            val rawHost = url.toUri().host?.takeIf { it.isNotEmpty() } ?: return null
            if (!explicit && !namesHost(rawHost)) return null
            val host = url.normalizedHost()?.takeIf { it.isNotEmpty() } ?: return null
            return TypedUrl(url, host)
        }

        private fun hasWebScheme(text: String): Boolean =
            text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)

        private fun namesHost(host: String): Boolean {
            val dotted = host.contains('.') && !host.endsWith('.')
            return dotted || host.equals("localhost", ignoreCase = true) || host.startsWith('[')
        }
    }
}
