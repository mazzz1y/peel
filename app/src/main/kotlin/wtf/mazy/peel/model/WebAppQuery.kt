package wtf.mazy.peel.model

import wtf.mazy.peel.util.TypedUrl
import wtf.mazy.peel.util.servesHost

fun WebApp.matchesQuery(query: String, groupTitles: Map<String, String>): Boolean {
    val needle = query.trim()
    return needle.isEmpty() ||
            title.contains(needle, ignoreCase = true) ||
            baseUrl.contains(needle, ignoreCase = true) ||
            groupUuid?.let(groupTitles::get)?.contains(needle, ignoreCase = true) == true
}

/**
 * The web apps a search for [query] keeps, in the order given, except that those serving the
 * host of a URL-shaped query come first.
 */
fun List<WebApp>.searched(
    query: String,
    groupTitles: Map<String, String>,
    typedHost: String? = if (query.isBlank()) null else TypedUrl.parse(query)?.host,
): List<WebApp> {
    if (query.isBlank()) return this
    val kept = filter { app ->
        app.matchesQuery(query, groupTitles) || typedHost != null && app.servesHost(typedHost)
    }
    typedHost ?: return kept
    val (onHost, rest) = kept.partition { it.servesHost(typedHost) }
    return onHost + rest
}
