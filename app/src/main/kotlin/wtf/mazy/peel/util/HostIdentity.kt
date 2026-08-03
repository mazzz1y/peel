package wtf.mazy.peel.util

import androidx.core.net.toUri

class HostIdentity private constructor(
    private val tld: String,
    private val labels: List<String>,
) {
    private val brand: String get() = labels.first()

    private fun affinityTo(other: HostIdentity): Int {
        if (tld != other.tld) return if (brand == other.brand) SAME_BRAND_OTHER_TLD else NO_MATCH
        val matched = matchedLabels(other)
        if (matched == 0) return TLD_ONLY
        val isOwnHost = matched == labels.size && matched == other.labels.size
        return score(matched, if (isOwnHost) TIER_APP_OWN_HOST else TIER_SAME_DOMAIN_OTHER_HOST)
    }

    private fun matchedLabels(other: HostIdentity): Int {
        var matched = 0
        for (i in 0 until minOf(labels.size, other.labels.size)) {
            if (labels[i] == other.labels[i]) matched++ else break
        }
        return matched
    }

    companion object {
        const val NO_MATCH = 0
        const val TLD_ONLY = 1
        private const val SAME_BRAND_OTHER_TLD = 2
        private const val LABEL_WEIGHT = 2

        private const val TIER_SAME_DOMAIN_OTHER_HOST = 0
        private const val TIER_SAME_APP_DOMAIN = 1
        private const val TIER_APP_OWN_HOST = 2

        private fun score(labels: Int, tier: Int) = TLD_ONLY + labels * LABEL_WEIGHT + tier

        private val COMPOUND_SECOND_LEVELS =
            setOf("ac", "co", "com", "edu", "gov", "mil", "net", "org")

        private fun parse(url: String): HostIdentity? {
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
            val app = parse(appBaseUrl) ?: return NO_MATCH
            val target = parse(targetUrl) ?: return NO_MATCH
            return app.affinityTo(target)
        }

        fun sameAppDomainAffinity(url: String): Int {
            val target = parse(url) ?: return NO_MATCH
            return score(target.labels.size, TIER_SAME_APP_DOMAIN)
        }
    }
}

fun String.normalizedHost(): String? = toUri().host?.removePrefix("www.")?.lowercase()
