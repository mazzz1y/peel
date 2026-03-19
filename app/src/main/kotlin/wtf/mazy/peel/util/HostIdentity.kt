package wtf.mazy.peel.util

import androidx.core.net.toUri

class HostIdentity private constructor(
    val tld: String,
    private val labels: List<String>,
) {
    val brand: String get() = labels.first()

    fun affinityTo(other: HostIdentity): Int {
        if (tld == other.tld) {
            var matched = 0
            for (i in 0 until minOf(labels.size, other.labels.size)) {
                if (labels[i] == other.labels[i]) matched++ else break
            }
            if (matched == 0) return TLD_ONLY
            val exact = matched == labels.size && matched == other.labels.size
            return TLD_ONLY + matched * 2 + if (exact) 1 else 0
        }
        return if (brand == other.brand) BRAND_ONLY else 0
    }

    companion object {
        const val TLD_ONLY = 1
        const val BRAND_ONLY = 2

        private val COMPOUND_SECOND_LEVELS =
            setOf("ac", "co", "com", "edu", "gov", "mil", "net", "org")

        fun parse(url: String): HostIdentity? {
            val host = url.toUri().host?.removePrefix("www.")?.lowercase() ?: return null
            val parts = host.split('.')
            if (parts.size < 2) return null
            val secondLevel = parts[parts.lastIndex - 1]
            val isCompound = parts.size >= 3 && secondLevel in COMPOUND_SECOND_LEVELS
            val tldSize = if (isCompound) 2 else 1
            val tld = parts.takeLast(tldSize).joinToString(".")
            val labels = parts.dropLast(tldSize)
            if (labels.isEmpty()) return null
            return HostIdentity(tld, labels.reversed())
        }

        fun affinity(appBaseUrl: String, targetUrl: String): Int {
            val app = parse(appBaseUrl) ?: return 0
            val target = parse(targetUrl) ?: return 0
            return app.affinityTo(target)
        }
    }
}
