package wtf.mazy.peel.util

/**
 * [subdomain] is outermost first. A host without a dot, and an IP address, is a domain of its own
 * with no [tld], so it only ever matches itself.
 */
data class HostIdentity(val name: String, val tld: String, val subdomain: List<String>) {

    val domain: String get() = if (tld.isEmpty()) name else "$name.$tld"

    companion object {
        private val COMPOUND_SECOND_LEVELS =
            setOf("ac", "co", "com", "edu", "gov", "mil", "net", "org")
        private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

        fun of(host: String): HostIdentity? {
            if (host.startsWith('[') || IPV4.matches(host)) return HostIdentity(host, "", emptyList())
            val parts = host.removePrefix("www.").split('.').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            if (parts.size == 1) return HostIdentity(parts.single(), "", emptyList())
            val isCompound = parts.size >= 3 && parts[parts.lastIndex - 1] in COMPOUND_SECOND_LEVELS
            val tldSize = if (isCompound) 2 else 1
            return HostIdentity(
                name = parts[parts.lastIndex - tldSize],
                tld = parts.takeLast(tldSize).joinToString("."),
                subdomain = parts.dropLast(tldSize + 1),
            )
        }
    }
}
