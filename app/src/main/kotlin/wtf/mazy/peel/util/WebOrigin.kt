package wtf.mazy.peel.util

import java.net.IDN

/** The scheme, host and port of a web address, with the port filled in when it is the default. */
data class WebOrigin(val scheme: String, val host: String, val port: Int) {

    /** The same site, whether or not either address is written with a leading `www.`. */
    fun isSamePlaceAs(other: WebOrigin): Boolean =
        scheme == other.scheme && port == other.port &&
                host.removePrefix(WWW) == other.host.removePrefix(WWW)

    companion object {
        private const val WWW = "www."
        private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
        private val HOST_NAME = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)*")
        private val IPV6_LITERAL = Regex("\\[[0-9a-f:.]+]")

        fun of(url: String): WebOrigin? {
            val separator = url.indexOf("://")
            if (separator <= 0) return null
            val scheme = url.substring(0, separator).lowercase()
            val defaultPort = DEFAULT_PORTS[scheme] ?: return null
            val rest = url.substring(separator + 3)
            val end = rest.indexOfAny(charArrayOf('/', '?', '#', '\\')).takeIf { it >= 0 } ?: rest.length
            val authority = rest.substring(0, end).substringAfterLast('@')
            val portStart = authority.lastIndexOf(':').takeIf { it > authority.lastIndexOf(']') } ?: -1
            val host = host(if (portStart >= 0) authority.substring(0, portStart) else authority)
                ?: return null
            val port = if (portStart < 0 || portStart == authority.lastIndex) defaultPort
            else authority.substring(portStart + 1).toIntOrNull()?.takeIf { it in 0..65535 }
            return port?.let { WebOrigin(scheme, host, it) }
        }

        private fun host(raw: String): String? {
            val lower = raw.lowercase()
            if (IPV6_LITERAL.matches(lower)) return lower
            val ascii = runCatching { IDN.toASCII(lower) }.getOrNull()?.lowercase()?.removeSuffix(".")
            return ascii?.takeIf { HOST_NAME.matches(it) }
        }
    }
}
