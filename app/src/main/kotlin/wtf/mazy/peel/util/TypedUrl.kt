package wtf.mazy.peel.util

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
            val url = normalize(trimmed)
            val origin = WebOrigin.of(url) ?: return null
            if (!hasWebScheme(trimmed) && !namesHost(origin.host)) return null
            return TypedUrl(url, origin.siteHost)
        }

        private fun hasWebScheme(text: String): Boolean =
            text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)

        private fun namesHost(host: String): Boolean =
            '.' in host || host == "localhost" || host.startsWith('[')
    }
}
