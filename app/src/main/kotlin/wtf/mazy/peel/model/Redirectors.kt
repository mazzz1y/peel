package wtf.mazy.peel.model

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A link and the two addresses Peel works with: [destination], where its chain of known
 * redirectors finally leads, which web apps are matched against, and [launchUrl], the address
 * actually opened.
 */
data class UnwrappedLink(val destination: String, val launchUrl: String) {
    constructor(url: String) : this(url, url)
}

/**
 * Redirectors that carry the address they lead to inside the link itself. Trackers only record
 * the click or warn about leaving their site, so they are skipped. Checks inspect the address
 * when it is followed, so they are matched through but still opened.
 */
object Redirectors {

    private const val MAX_LAYERS = 4

    private val trackers = listOf(
        Redirector(::isGoogleHost, paths("/url"), params("url", "q")),
        Redirector(::isGoogleHost, { it.startsWith(AMP_PREFIX) }, ::ampTarget),
        Redirector(hosts("youtube.com", "www.youtube.com", "m.youtube.com"), paths("/redirect"), params("q")),
        Redirector(hosts("l.facebook.com", "lm.facebook.com"), paths("/l.php"), params("u")),
        Redirector(hosts("l.messenger.com"), paths("/l.php"), params("u")),
        Redirector(hosts("l.instagram.com"), paths("/"), params("u")),
        Redirector(hosts("vk.com", "m.vk.com"), paths("/away.php"), params("to")),
        Redirector(hosts("linkedin.com", "www.linkedin.com"), paths("/redir/redirect", "/safety/go"), params("url")),
        Redirector(hosts("out.reddit.com"), { true }, params("url")),
        Redirector(hosts("duckduckgo.com"), paths("/l/"), params("uddg")),
        Redirector(hosts("steamcommunity.com"), paths("/linkfilter/", "/linkfilter"), params("url")),
        Redirector(hosts("t.umblr.com"), paths("/redirect"), params("z")),
        Redirector(hosts("slack-redir.net"), paths("/link"), params("url")),
    )

    private val checks = listOf(
        Redirector({ it.endsWith(".safelinks.protection.outlook.com") }, paths("/"), params("url")),
    )

    fun unwrap(url: String): UnwrappedLink {
        var current = url
        var checkedAt: String? = null
        var layersLeft = MAX_LAYERS
        while (layersLeft-- > 0) {
            val uri = parseWebUrl(current) ?: break
            val skipped = trackers.firstNotNullOfOrNull { it.target(uri) }
            if (skipped != null) {
                current = skipped
                continue
            }
            val checked = checks.firstNotNullOfOrNull { it.target(uri) } ?: break
            if (checkedAt == null) checkedAt = current
            current = checked
        }
        return UnwrappedLink(destination = current, launchUrl = checkedAt ?: current)
    }
}

private class Redirector(
    private val host: (String) -> Boolean,
    private val path: (String) -> Boolean,
    private val extract: (URI) -> String?,
) {
    fun target(uri: URI): String? =
        if (host(uri.host.lowercase()) && path(uri.rawPath.ifEmpty { "/" })) extract(uri) else null
}

private const val AMP_PREFIX = "/amp/s/"

private val WEB_SCHEMES = setOf("http", "https")

private val GOOGLE_HOST = Regex("""(?:www\.)?google\.(?:com?\.)?[a-z]{2,3}""")

private fun isGoogleHost(host: String): Boolean = GOOGLE_HOST.matches(host)

private fun hosts(vararg names: String): (String) -> Boolean = names.toSet()::contains

private fun paths(vararg values: String): (String) -> Boolean = values.toSet()::contains

private fun params(vararg names: String): (URI) -> String? =
    { uri -> names.firstNotNullOfOrNull { name -> uri.queryParam(name)?.takeIf(::isWebUrl) } }

private fun ampTarget(uri: URI): String? {
    val tail = uri.rawPath.removePrefix(AMP_PREFIX).ifEmpty { return null }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    return "https://$tail$query$fragment".takeIf(::isWebUrl)
}

private fun isWebUrl(url: String): Boolean = parseWebUrl(url) != null

private fun parseWebUrl(url: String): URI? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    return uri.takeIf { it.scheme?.lowercase() in WEB_SCHEMES && !it.host.isNullOrEmpty() }
}

private fun URI.queryParam(name: String): String? =
    rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
        if ('=' in pair && pair.substringBefore('=') == name) percentDecode(pair.substringAfter('=')) else null
    }

// The values are addresses, not form text: a '+' in them is a plus sign, not an encoded space.
private fun percentDecode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
