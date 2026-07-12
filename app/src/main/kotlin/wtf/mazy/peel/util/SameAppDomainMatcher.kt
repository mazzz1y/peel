package wtf.mazy.peel.util

import java.util.concurrent.ConcurrentHashMap

object SameAppDomainMatcher {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun matches(url: String, entries: List<String>): Boolean {
        if (entries.isEmpty()) return false
        val host = url.normalizedHost() ?: return false
        return entries.any { entry -> matchesEntry(host, entry.trim()) }
    }

    private fun matchesEntry(host: String, entry: String): Boolean {
        if (entry.isEmpty()) return false
        val pattern = regexPatternOrNull(entry)
        if (pattern != null) {
            val regex = compile(pattern) ?: return false
            return regex.matches(host)
        }
        val domain = entry.removePrefix("www.").lowercase()
        return host == domain || host.endsWith(".$domain")
    }

    fun regexPatternOrNull(entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.length < 2) return null
        if (!trimmed.startsWith('/') || !trimmed.endsWith('/')) return null
        return trimmed.substring(1, trimmed.length - 1)
    }

    fun isValid(entry: String): Boolean {
        val pattern = regexPatternOrNull(entry) ?: return true
        return compile(pattern) != null
    }

    private fun compile(pattern: String): Regex? =
        regexCache[pattern] ?: runCatching { Regex(pattern) }.getOrNull()
            ?.also { regexCache[pattern] = it }
}
