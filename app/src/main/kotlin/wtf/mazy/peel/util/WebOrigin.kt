package wtf.mazy.peel.util

import java.net.IDN
import java.net.URI

/** The scheme, host and port of a web address, with the port filled in when it is the default. */
data class WebOrigin(val scheme: String, val host: String, val port: Int) {

    val hasDefaultPort: Boolean get() = port == (DEFAULT_PORTS[scheme] ?: NO_PORT)

    val siteHost: String get() = host.removePrefix(WWW)

    /** The same site, whether or not either address is written with a leading `www.`. */
    fun isSamePlaceAs(other: WebOrigin): Boolean =
        scheme == other.scheme && port == other.port && siteHost == other.siteHost

    companion object {
        private const val WWW = "www."
        private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
        private const val NO_PORT = -1
        private val HOST_NAME = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)*")
        private val IPV6_LITERAL = Regex("\\[[0-9a-f:.]+]")
        internal val AUTHORITY_END = charArrayOf('/', '?', '#', '\\')

        fun of(url: String): WebOrigin? {
            val origin = anyOf(url) ?: return null
            return origin.takeIf { it.scheme in DEFAULT_PORTS }
        }

        // Same-app and blocked-domain rules match any scheme's host (an `intent://` link to a
        // blocked domain is still blocked); a port of -1 means none was given.
        fun anyOf(url: String): WebOrigin? {
            val separator = url.indexOf("://")
            if (separator <= 0) return null
            val scheme = url.substring(0, separator).lowercase()
            val defaultPort = DEFAULT_PORTS[scheme] ?: NO_PORT
            val rest = url.substring(separator + 3)
            val end = rest.indexOfAny(AUTHORITY_END).takeIf { it >= 0 } ?: rest.length
            val authority = rest.substring(0, end).substringAfterLast('@')
            val portStart = authority.lastIndexOf(':').takeIf { it > authority.lastIndexOf(']') } ?: -1
            val host = host(if (portStart >= 0) authority.substring(0, portStart) else authority)
                ?: return null
            val port = if (portStart < 0 || portStart == authority.lastIndex) defaultPort
            else authority.substring(portStart + 1).takeIf { it.all(Char::isDigit) }
                ?.toIntOrNull()?.takeIf { it in 0..65535 }
            return port?.let { WebOrigin(scheme, host, it) }
        }

        fun host(raw: String): String? {
            val lower = raw.lowercase()
            if (IPV6_LITERAL.matches(lower)) return lower
            val ascii = runCatching { IDN.toASCII(lower) }.getOrNull()?.lowercase()?.removeSuffix(".")
            return ascii?.takeIf { HOST_NAME.matches(it) }
        }
    }
}

/** [path] is decoded, [rawPath] as written; both are `/` when the address has none. */
data class WebAddress(val origin: WebOrigin, val path: String, val rawPath: String, val query: String?) {

    val normalized: String
        get() = buildString {
            append(origin.scheme).append("://").append(origin.host)
            if (!origin.hasDefaultPort) append(':').append(origin.port)
            append(rawPath)
            query?.let { append('?').append(it) }
        }

    companion object {
        fun of(url: String): WebAddress? = WebOrigin.of(url)?.let { of(url, it) }

        fun anyOf(url: String): WebAddress? = WebOrigin.anyOf(url)?.let { of(url, it) }

        private fun of(url: String, origin: WebOrigin): WebAddress {
            val rest = url.substringAfter("://")
            val start = rest.indexOfAny(WebOrigin.AUTHORITY_END).takeIf { it >= 0 } ?: rest.length
            val tail = rest.substring(start).substringBefore('#')
            val rawPath = tail.substringBefore('?').replace('\\', '/').ifEmpty { "/" }
            val query = if ('?' in tail) tail.substringAfter('?') else null
            return WebAddress(origin, decode(rawPath), rawPath, query)
        }

        // A path that is not valid URI syntax is kept as written.
        private fun decode(path: String): String =
            if ('%' !in path) path else runCatching { URI("http://h$path").path }.getOrNull() ?: path
    }
}

fun String.siteHost(): String? = WebOrigin.of(this)?.siteHost

fun isSameHost(a: String, b: String): Boolean {
    val host = a.siteHost() ?: return false
    return host == b.siteHost()
}
