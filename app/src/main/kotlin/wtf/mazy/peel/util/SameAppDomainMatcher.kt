package wtf.mazy.peel.util

import java.util.concurrent.ConcurrentHashMap
import wtf.mazy.peel.model.UrlRule

object SameAppDomainMatcher {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun matches(url: String, entries: List<String>): Boolean = matching(url, entries).isNotEmpty()

    /** Every entry of [entries] that [url] matches. */
    fun matching(url: String, entries: List<String>): List<UrlRule> {
        if (entries.isEmpty()) return emptyList()
        val address = WebAddress.anyOf(url) ?: return emptyList()
        return entries.mapNotNull { UrlRule.parse(it) }.filter { matchesEntry(address, it) }
    }

    private fun matchesEntry(address: WebAddress, entry: UrlRule): Boolean {
        val host = address.origin.siteHost
        return when (entry) {
            is UrlRule.Domain -> host == entry.value || host.endsWith(".${entry.value}")
            is UrlRule.UrlPrefix -> host == entry.host && pathMatches(address.path, entry.path)
            is UrlRule.UrlRegex -> compile(entry.value)?.matches(address.normalized) == true
            is UrlRule.HostRegex -> compile(entry.value)?.matches(host) == true
        }
    }

    private fun pathMatches(path: String, prefix: String): Boolean =
        prefix == "/" || path == prefix || path.startsWith("$prefix/")

    private fun compile(pattern: String): Regex? =
        regexCache[pattern] ?: runCatching { Regex(pattern) }.getOrNull()
            ?.also { regexCache[pattern] = it }
}
