package wtf.mazy.peel.model

import wtf.mazy.peel.util.TypedUrl
import wtf.mazy.peel.util.WebOrigin
import wtf.mazy.peel.util.address
import wtf.mazy.peel.util.bestBy
import wtf.mazy.peel.util.linkMatch

fun WebApp.matchesQuery(query: String, groupTitles: Map<String, String>): Boolean {
    val needle = query.trim()
    return needle.isEmpty() ||
            title.contains(needle, ignoreCase = true) ||
            baseUrl.contains(needle, ignoreCase = true) ||
            groupUuid?.let(groupTitles::get)?.contains(needle, ignoreCase = true) == true
}

fun WebApp.isOnHost(host: String): Boolean = WebOrigin.of(baseUrl)?.siteHost == host

/**
 * The web apps a search for [query] keeps, in the order given, except that those on the host of
 * a URL-shaped query come first.
 */
fun List<WebApp>.searched(
    query: String,
    groupTitles: Map<String, String>,
    typedHost: String? = if (query.isBlank()) null else TypedUrl.parse(query)?.host,
): List<WebApp> {
    if (query.isBlank()) return this
    val kept = filter { app ->
        app.matchesQuery(query, groupTitles) || typedHost != null && app.isOnHost(typedHost)
    }
    typedHost ?: return kept
    val (onHost, rest) = kept.partition { it.isOnHost(typedHost) }
    return onHost + rest
}

/** Null when the best match is tied or too weak. */
fun List<WebApp>.bestFor(url: String): WebApp? = bestBy { it.linkMatch(url) }

fun List<WebApp>.onHostOf(url: String): List<WebApp> {
    val host = WebOrigin.of(url)?.siteHost ?: return emptyList()
    return filter { it.isOnHost(host) }
}

// Private web apps don't count: adding the site as a real web app is still meaningful.
fun List<WebApp>.hasWebAppAt(url: String, sandboxOf: (WebApp) -> EffectiveSandbox): Boolean {
    val target = WebOrigin.of(url) ?: return false
    return any { app ->
        sandboxOf(app) !is EffectiveSandbox.Private &&
                WebOrigin.of(app.address)?.isSamePlaceAs(target) == true
    }
}
