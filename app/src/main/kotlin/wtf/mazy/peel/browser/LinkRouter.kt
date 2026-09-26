package wtf.mazy.peel.browser

import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.util.SameAppDomainMatcher
import wtf.mazy.peel.util.belongsToApp

sealed interface LinkRoute {
    data object Allow : LinkRoute
    data object Blocked : LinkRoute
    data object Refused : LinkRoute
    data object AppLink : LinkRoute
    data class Redirect(val target: String) : LinkRoute
    data class PromptExternal(
        val target: String,
        val wasUpgraded: Boolean,
        val opensNewWindow: Boolean,
    ) : LinkRoute
}

data class NavigationFacts(
    val hasUserGesture: Boolean,
    val isRedirect: Boolean,
    val opensNewWindow: Boolean,
    val isDirectNavigation: Boolean,
    val hasTriggerPage: Boolean,
)

data class LinkContext(
    val policyOrigin: String,
    val browsingExternally: Boolean,
    val isInitialLoad: Boolean,
    val hasPeelAppMatch: (String) -> Boolean,
)

object LinkRouter {

    fun route(
        url: String,
        settings: EffectiveSettings,
        nav: NavigationFacts,
        context: LinkContext,
    ): LinkRoute = when {
        url.isBlank() -> LinkRoute.Allow
        isBlocked(url, settings) -> LinkRoute.Blocked
        url.startsWith("data:") && nav.isDirectNavigation -> LinkRoute.Refused
        !isBrowserScheme(url) -> LinkRoute.AppLink
        isPassthroughScheme(url) -> LinkRoute.Allow
        else -> routeBrowserLoad(url, settings, nav, context)
    }

    private fun routeBrowserLoad(
        url: String,
        settings: EffectiveSettings,
        nav: NavigationFacts,
        context: LinkContext,
    ): LinkRoute {
        if (!isHttpUrl(url)) return LinkRoute.Allow
        val target = settings.upgradeUrl(url)
        if (settings.openUrlExternal &&
            shouldRouteExternally(target, settings, nav, context)
        ) {
            return LinkRoute.PromptExternal(
                target,
                wasUpgraded = target != url,
                opensNewWindow = nav.opensNewWindow,
            )
        }
        return if (target != url) LinkRoute.Redirect(target) else LinkRoute.Allow
    }

    private fun shouldRouteExternally(
        url: String,
        settings: EffectiveSettings,
        nav: NavigationFacts,
        context: LinkContext,
    ): Boolean {
        if (context.browsingExternally || context.isInitialLoad) return false
        if (belongsToApp(context.policyOrigin, url, settings)) return false
        if (isExplicitDownload(url)) return false
        return context.hasPeelAppMatch(url) || nav.hasUserGesture || nav.isRedirect ||
                nav.opensNewWindow || nav.hasTriggerPage
    }

    private fun isBlocked(url: String, settings: EffectiveSettings): Boolean =
        SameAppDomainMatcher.matches(url, settings.blockedDomains)

    fun isBrowserScheme(url: String): Boolean =
        BROWSER_SCHEMES.any { url.startsWith(it) }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private fun isPassthroughScheme(url: String): Boolean =
        PASSTHROUGH_SCHEMES.any { url.startsWith(it) }

    private fun isExplicitDownload(url: String): Boolean {
        val query = url.substringAfter('?', "").lowercase()
        if (query.isEmpty()) return false
        return "response-content-disposition=attachment" in query ||
                "rscd=attachment" in query
    }

    private val BROWSER_SCHEMES = arrayOf(
        "http://", "https://", "moz-extension://",
        "file://", "about:", "blob:", "data:",
    )

    private val PASSTHROUGH_SCHEMES = arrayOf("blob:", "data:", "moz-extension://")
}
