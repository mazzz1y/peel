package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerRoutesTest {

    private val proxyA = Proxy(uuid = "proxy-a", name = "Proxy A")
    private val global = WebApp(baseUrl = "", uuid = "global", title = "")

    private fun app(uuid: String, isolation: Isolation<ProxyChoice>, groupUuid: String? = null) =
        WebApp(baseUrl = "https://$uuid", uuid = uuid, title = uuid, isolation = isolation, groupUuid = groupUuid)

    private fun state(
        webApps: List<WebApp>,
        groups: List<WebAppGroup> = emptyList(),
        proxies: List<Proxy> = listOf(proxyA),
    ) = DataState(webApps = webApps, groups = groups, globalSettings = global, proxies = proxies)

    @Test
    fun routesEveryOwnSandboxOnce() {
        val group = WebAppGroup(uuid = "work", isolation = Isolation.Sandboxed(false, ProxyRoute.Via("proxy-a")))
        val routes = ContainerRoutes.of(
            state(
                webApps = listOf(
                    app("shared", Isolation.Shared, groupUuid = "work"),
                    app("inherit", Isolation.Sandboxed(false, ProxyChoice.Inherit), groupUuid = "work"),
                    app("direct", Isolation.Sandboxed(false, ProxyRoute.Direct), groupUuid = "work"),
                ),
                groups = listOf(group),
            ),
            transientWebApps = emptyList(),
        )
        assertEquals(
            mapOf(
                "work" to ProxyRoute.Via("proxy-a"),
                "inherit" to ProxyRoute.Via("proxy-a"),
                "direct" to ProxyRoute.Direct,
            ),
            routes,
        )
    }

    @Test
    fun ownProxyInsideUnsandboxedGroup() {
        val group = WebAppGroup(uuid = "plain", isolation = Isolation.Shared)
        val routes = ContainerRoutes.of(
            state(
                webApps = listOf(app("own", Isolation.Sandboxed(false, ProxyRoute.Via("proxy-a")), groupUuid = "plain")),
                groups = listOf(group),
            ),
            transientWebApps = emptyList(),
        )
        assertEquals(mapOf("own" to ProxyRoute.Via("proxy-a")), routes)
    }

    @Test
    fun unknownProxyIsLeftOutSoTheRouterFailsClosed() {
        val routes = ContainerRoutes.of(
            state(
                webApps = listOf(app("stale", Isolation.Sandboxed(false, ProxyRoute.Via("gone")))),
            ),
            transientWebApps = emptyList(),
        )
        assertEquals(emptyMap<String, ProxyRoute>(), routes)
    }

    @Test
    fun transientWebAppsAreRouted() {
        val routes = ContainerRoutes.of(
            state(webApps = emptyList()),
            transientWebApps = listOf(app("transient", Isolation.Sandboxed(false, ProxyRoute.Via("proxy-a")))),
        )
        assertEquals(mapOf("transient" to ProxyRoute.Via("proxy-a")), routes)
    }

    @Test
    fun privateAndSharedAppsAreNotRouted() {
        val incognito = app("incognito", Isolation.Shared).copy(isPrivateSession = true)
        val routes = ContainerRoutes.of(
            state(webApps = listOf(app("plain", Isolation.Shared))),
            transientWebApps = listOf(incognito),
        )
        assertEquals(emptyMap<String, ProxyRoute>(), routes)
    }
}
