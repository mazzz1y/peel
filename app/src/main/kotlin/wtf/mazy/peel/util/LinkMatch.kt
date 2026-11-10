package wtf.mazy.peel.util

import wtf.mazy.peel.model.EquivalentDomains
import wtf.mazy.peel.model.UrlRule

/** How the registrable domains of a link and a web app relate, weakest first. */
enum class DomainRelation { NONE, SAME_TLD, SAME_NAME, EQUIVALENT, SAME }

/** What matched the link for a web app, weakest first, deciding between equal matches. */
enum class MatchSource(val namesPath: Boolean) {
    HOST_REGEX(false), URL_REGEX(true), DOMAIN(false), URL_PREFIX(true), OWN_ADDRESS(true),
}

/**
 * A web app ranked for a link: by [relation], then by how many subdomain labels match in a row
 * from the domain ([chain]), then by whether it names a part of the link's own host ([placed]),
 * then by the address words the two share less those of the web app the link lacks ([score]),
 * then by whether it is on that host ([sameHost]), then by what matched ([source]).
 */
data class LinkMatch(
    val relation: DomainRelation,
    val chain: Int = 0,
    val score: Int = 0,
    val sameHost: Boolean = false,
    val source: MatchSource = MatchSource.OWN_ADDRESS,
) : Comparable<LinkMatch> {

    /** Whether the link is part of the web app, as opposed to merely related to it. */
    val belongs: Boolean
        get() = relation == DomainRelation.SAME || relation == DomainRelation.SAME_NAME

    // A host-only entry (Domain, host regex) on the link's host claims the host, not a part of
    // it, so any address there that names a path comes first, the host's own app's included.
    private val placed: Boolean get() = !sameHost || source.namesPath

    override fun compareTo(other: LinkMatch): Int = compareValuesBy(
        this, other,
        LinkMatch::relation, LinkMatch::chain, LinkMatch::placed,
        LinkMatch::score, LinkMatch::sameHost, LinkMatch::source,
    )

    companion object {
        val NONE = LinkMatch(DomainRelation.NONE)
    }
}

/**
 * How [url] relates to a web app at [appAddress] whose same-app addresses matching a URL are
 * [entriesFor] it. Each matching entry ranks as the web app's own address would.
 */
fun linkMatch(appAddress: String, url: String, entriesFor: (String) -> List<UrlRule>): LinkMatch {
    val link = Address.of(url) ?: return LinkMatch.NONE
    val own = Address.of(appAddress)?.matchTo(link) ?: LinkMatch.NONE
    return entriesFor(url).fold(own) { best, entry -> maxOf(best, entryMatch(entry, link)) }
}

/** The single item ranked above all others, if it relates to the link by more than its TLD. */
fun <T> List<T>.bestBy(match: (T) -> LinkMatch): T? {
    val matches = associateWith(match)
    val top = matches.values.maxOrNull()?.takeIf { it.relation > DomainRelation.SAME_TLD }
        ?: return null
    return matches.filterValues { it == top }.keys.singleOrNull()
}

// The entry already matched, so it relates the link at least as its own domain would: a Domain
// entry such as `lan` covers hosts that share no registrable domain with it.
private fun entryMatch(entry: UrlRule, link: Address): LinkMatch {
    val (match, source) = when (entry) {
        is UrlRule.Domain -> Address.of("https://${entry.value}/")?.matchTo(link) to MatchSource.DOMAIN
        is UrlRule.UrlPrefix -> Address.of("https://${entry.host}${entry.path}")
            ?.matchTo(link) to MatchSource.URL_PREFIX
        is UrlRule.UrlRegex -> link.fullMatch() to MatchSource.URL_REGEX
        is UrlRule.HostRegex -> link.atHost().matchTo(link) to MatchSource.HOST_REGEX
    }
    return maxOf(match ?: LinkMatch.NONE, LinkMatch(DomainRelation.SAME)).copy(source = source)
}

private class Address(
    val host: HostIdentity,
    val port: Int?,
    val segments: List<String>,
) {

    val portWord: String? = port?.let { ":$it" }

    val words: Map<String, Int> = buildMap {
        host.subdomain.forEach { addWithParts(it, HOST_WEIGHT) }
        portWord?.let { put(it, HOST_WEIGHT) }
        segments.forEachIndexed { depth, segment -> addWithParts(segment, segmentWeight(depth)) }
    }

    private fun MutableMap<String, Int>.addWithParts(text: String, weight: Int) {
        add(text, weight)
        text.split(*WORD_SEPARATORS).forEach { add(it, weight) }
    }

    private fun MutableMap<String, Int>.add(word: String, weight: Int) {
        if (!isNoise(word)) merge(word, weight, ::maxOf)
    }

    fun atHost(): Address = Address(host, port, emptyList())

    fun matchTo(link: Address): LinkMatch {
        val relation = relationTo(link.host)
        if (relation < DomainRelation.SAME_NAME) return LinkMatch(relation)
        val same = relation == DomainRelation.SAME
        val chain = if (same) {
            host.subdomain.asReversed().zip(link.host.subdomain.asReversed())
                .takeWhile { (a, b) -> a == b }.size
        } else 0
        return LinkMatch(
            relation = relation,
            chain = chain,
            score = 2 * (sharedWeight(link) + pathWeight(link)) - missingWeight(link),
            sameHost = same && host.subdomain == link.host.subdomain,
        )
    }

    fun fullMatch(): LinkMatch =
        LinkMatch(DomainRelation.SAME, host.subdomain.size, 2 * words.values.sum(), sameHost = true)

    private fun relationTo(other: HostIdentity): DomainRelation {
        if (host.domain == other.domain) return DomainRelation.SAME
        val bothHaveTld = host.tld.isNotEmpty() && other.tld.isNotEmpty()
        if (bothHaveTld && host.name == other.name) return DomainRelation.SAME_NAME
        val site = EquivalentDomains.siteOf(host.domain)
        val otherSite = EquivalentDomains.siteOf(other.domain)
        if (site != null || otherSite != null) {
            val a = HostIdentity.of(site ?: host.domain)
            val b = HostIdentity.of(otherSite ?: other.domain)
            if (a != null && b != null && (a.domain == b.domain || a.name == b.name)) {
                return DomainRelation.EQUIVALENT
            }
        }
        return if (bothHaveTld && host.tld == other.tld) DomainRelation.SAME_TLD else DomainRelation.NONE
    }

    private fun sharedWeight(link: Address): Int =
        words.entries.sumOf { (word, weight) -> link.words[word]?.let { minOf(it, weight) } ?: 0 }

    // A path the link starts with counts in full, even its segments too short or numeric to
    // count as words (`/mail/u/1`): following it is evidence by itself.
    private fun pathWeight(link: Address): Int {
        if (segments.isEmpty() || link.segments.take(segments.size) != segments) return 0
        return segments.withIndex().filter { (_, segment) -> isNoise(segment) }
            .sumOf { (depth, _) -> segmentWeight(depth) }
    }

    private fun missingWeight(link: Address): Int {
        val labels = host.subdomain.count { !isNoise(it) && it !in link.words }
        val port = if (portWord != null && portWord !in link.words) 1 else 0
        val leftAt = segments.indices.firstOrNull { it >= link.segments.size || link.segments[it] != segments[it] }
        return (labels + port) * HOST_WEIGHT + (leftAt?.let(::segmentWeight) ?: 0)
    }

    companion object {
        private const val HOST_WEIGHT = 8
        private val SEGMENT_WEIGHTS = intArrayOf(8, 4, 2, 1)
        private val WORD_SEPARATORS = charArrayOf('-', '_', '.', '~', '+')
        private const val ID_LENGTH = 8

        private fun segmentWeight(depth: Int): Int = SEGMENT_WEIGHTS[minOf(depth, SEGMENT_WEIGHTS.lastIndex)]

        private fun isNoise(word: String): Boolean =
            word.length < 2 || word.all(Char::isDigit) ||
                    (word.length >= ID_LENGTH && word.any(Char::isDigit) && word.any(Char::isLetter))

        fun of(url: String): Address? {
            val address = WebAddress.of(url) ?: return null
            val origin = address.origin
            return Address(
                host = HostIdentity.of(origin.host) ?: return null,
                port = origin.port.takeUnless { origin.hasDefaultPort },
                segments = address.path.split('/').filter { it.isNotEmpty() }.map { it.lowercase() },
            )
        }
    }
}
