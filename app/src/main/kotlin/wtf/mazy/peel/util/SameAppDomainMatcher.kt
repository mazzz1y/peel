package wtf.mazy.peel.util

import androidx.core.net.toUri
import java.util.concurrent.ConcurrentHashMap
import wtf.mazy.peel.model.UrlRule

object SameAppDomainMatcher {

    private class MatchTarget(val host: String, val path: String, val url: String)

    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun matches(url: String, entries: List<String>): Boolean =
        mostSpecificMatch(url, entries) != null

    fun mostSpecificMatch(url: String, entries: List<String>): UrlRule? {
        if (entries.isEmpty()) return null
        val target = target(url) ?: return null
        var best: UrlRule? = null
        for (stored in entries) {
            val entry = UrlRule.parse(stored) ?: continue
            if (!matchesEntry(target, entry)) continue
            if (entry is UrlRule.UrlPrefix) return entry
            if (best == null) best = entry
        }
        return best
    }

    private fun matchesEntry(target: MatchTarget, entry: UrlRule): Boolean =
        when (entry) {
            is UrlRule.Domain -> hostMatches(target.host, entry.value)
            is UrlRule.UrlPrefix ->
                target.host == entry.host && pathMatches(target.path, entry.path)
            is UrlRule.UrlRegex -> compile(entry.value)?.matches(target.url) == true
            is UrlRule.HostRegex -> compile(entry.value)?.matches(target.host) == true
        }

    private fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun pathMatches(path: String, prefix: String): Boolean =
        prefix == "/" || path == prefix || path.startsWith("$prefix/")

    private fun compile(pattern: String): Regex? =
        regexCache[pattern] ?: runCatching { Regex(pattern) }.getOrNull()
            ?.also { regexCache[pattern] = it }

    private fun target(url: String): MatchTarget? {
        val uri = url.toUri()
        val rawHost = uri.host?.let(UrlRule::asciiHost) ?: return null
        val path = uri.path.orEmpty().ifEmpty { "/" }
        val builder = StringBuilder()
            .append(uri.scheme.orEmpty()).append("://").append(rawHost)
        if (uri.port != -1) builder.append(':').append(uri.port)
        builder.append(uri.encodedPath.orEmpty().ifEmpty { "/" })
        uri.encodedQuery?.let { builder.append('?').append(it) }
        return MatchTarget(rawHost.removePrefix("www."), path, builder.toString())
    }
}
