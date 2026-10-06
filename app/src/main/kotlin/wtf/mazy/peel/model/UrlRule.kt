package wtf.mazy.peel.model

import androidx.core.net.toUri
import java.net.IDN

sealed interface UrlRule {

    val value: String
    val stored: String

    data class Domain(override val value: String) : UrlRule {
        override val stored: String get() = value
    }

    data class UrlPrefix(val host: String, val path: String) : UrlRule {
        override val value: String get() = host + path
        override val stored: String get() = URL_PREFIX_TAG + value
    }

    data class UrlRegex(override val value: String) : UrlRule {
        override val stored: String get() = URL_REGEX_TAG + value
    }

    data class HostRegex(override val value: String) : UrlRule {
        override val stored: String get() = HOST_REGEX_TAG + value
        val asUrlRegex: UrlRegex get() = UrlRegex(wrapHostPattern(value))
    }

    enum class Kind { DOMAIN, URL_PREFIX, URL_REGEX, HOST_REGEX }

    enum class Problem { EMPTY, INVALID_HOST, HAS_PATH, NEEDS_PATH, INVALID_REGEX }

    val kind: Kind
        get() = when (this) {
            is Domain -> Kind.DOMAIN
            is UrlPrefix -> Kind.URL_PREFIX
            is UrlRegex -> Kind.URL_REGEX
            is HostRegex -> Kind.HOST_REGEX
        }

    companion object {
        private const val URL_PREFIX_TAG = "url:"
        private const val URL_REGEX_TAG = "re:"
        private const val HOST_REGEX_TAG = "host-re:"
        private val HOST_PATTERN = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*")

        fun parse(stored: String): UrlRule? {
            val trimmed = stored.trim()
            return when {
                trimmed.startsWith(URL_REGEX_TAG) ->
                    UrlRegex(trimmed.removePrefix(URL_REGEX_TAG))
                trimmed.startsWith(HOST_REGEX_TAG) ->
                    HostRegex(trimmed.removePrefix(HOST_REGEX_TAG))
                trimmed.startsWith(URL_PREFIX_TAG) ->
                    splitHostPath(trimmed.removePrefix(URL_PREFIX_TAG))
                        ?.let { (host, path) -> UrlPrefix(foldHost(host), path) }
                trimmed.isEmpty() || '/' in trimmed || ':' in trimmed -> null
                else -> Domain(foldHost(trimmed))
            }
        }

        fun problem(kind: Kind, input: String): Problem? {
            val text = input.trim()
            if (text.isEmpty()) return Problem.EMPTY
            if (kind == Kind.URL_REGEX || kind == Kind.HOST_REGEX) {
                return if (runCatching { Regex(text) }.isFailure) Problem.INVALID_REGEX else null
            }
            val (host, path) = hostAndPath(text)
            return when {
                !HOST_PATTERN.matches(host) -> Problem.INVALID_HOST
                kind == Kind.DOMAIN && path.length > 1 -> Problem.HAS_PATH
                kind == Kind.URL_PREFIX && path.length <= 1 -> Problem.NEEDS_PATH
                else -> null
            }
        }

        fun canonical(kind: Kind, input: String): UrlRule {
            val text = input.trim()
            return when (kind) {
                Kind.DOMAIN -> Domain(hostAndPath(text).first)
                Kind.URL_PREFIX -> hostAndPath(text).let { (host, path) -> UrlPrefix(host, path) }
                Kind.URL_REGEX -> UrlRegex(text)
                Kind.HOST_REGEX -> HostRegex(text)
            }
        }

        fun normalizeLegacy(stored: String): String {
            val trimmed = stored.trim()
            if (trimmed.length < 2 || !trimmed.startsWith('/') || !trimmed.endsWith('/')) return stored
            return HOST_REGEX_TAG + trimmed.substring(1, trimmed.length - 1)
        }

        private fun wrapHostPattern(pattern: String): String {
            val inner = pattern
                .removePrefix("^")
                .let { if (it.endsWith("$") && !it.endsWith("\\$")) it.dropLast(1) else it }
            return "^https?://(?:www\\.)?(?:$inner)(?::\\d+)?(?:[/?].*)?$"
        }

        private fun hostAndPath(input: String): Pair<String, String> {
            val withScheme = if ("://" in input) input else "https://$input"
            val uri = withScheme.toUri()
            val host = uri.host?.let(::foldHost) ?: return input.lowercase() to "/"
            val path = uri.path.orEmpty().ifEmpty { "/" }
                .let { if (it.length > 1) it.trimEnd('/') else it }
            return host to path
        }

        fun asciiHost(host: String): String =
            runCatching { IDN.toASCII(host) }.getOrDefault(host).lowercase().removeSuffix(".")

        private fun foldHost(host: String): String = asciiHost(host).removePrefix("www.")

        private fun splitHostPath(value: String): Pair<String, String>? {
            val slash = value.indexOf('/')
            if (slash <= 0) return null
            val path = value.substring(slash).let { if (it.length > 1) it.trimEnd('/') else it }
            return value.substring(0, slash) to path
        }
    }
}
