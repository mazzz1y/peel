package wtf.mazy.peel.util

import androidx.core.net.toUri
import wtf.mazy.peel.model.WebApp

/**
 * A host split into its registrable domain's [name] and [tld], and the [subdomain] labels before
 * it, outermost first. A host without a dot is a domain of its own with no [tld].
 */
data class HostIdentity(val name: String, val tld: String, val subdomain: List<String>) {

    val domain: String get() = if (tld.isEmpty()) name else "$name.$tld"

    companion object {
        private val COMPOUND_SECOND_LEVELS =
            setOf("ac", "co", "com", "edu", "gov", "mil", "net", "org")

        fun of(host: String): HostIdentity? {
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

fun String.normalizedHost(): String? = toUri().host?.lowercase()?.removePrefix("www.")

fun WebApp.servesHost(host: String): Boolean = baseUrl.normalizedHost() == host
