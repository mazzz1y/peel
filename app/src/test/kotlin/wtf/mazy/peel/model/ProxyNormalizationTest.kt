package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProxyNormalizationTest {

    private val existing = setOf("proxy-a")

    private fun app(isolation: Isolation<ProxyChoice>) =
        WebApp(baseUrl = "https://example.com", uuid = "app", title = "app", isolation = isolation)

    private fun group(isolation: Isolation<ProxyRoute>) =
        WebAppGroup(uuid = "group", isolation = isolation)

    @Test
    fun danglingWebAppProxyBecomesInherit() {
        val webApp = app(Isolation.Sandboxed(true, ProxyRoute.Via("gone")))
        assertEquals(
            Isolation.Sandboxed(true, ProxyChoice.Inherit),
            webApp.withExistingProxy(existing).isolation,
        )
    }

    @Test
    fun danglingGroupProxyBecomesDirect() {
        val group = group(Isolation.Sandboxed(false, ProxyRoute.Via("gone")))
        assertEquals(
            Isolation.Sandboxed(false, ProxyRoute.Direct),
            group.withExistingProxy(existing).isolation,
        )
    }

    @Test
    fun unchangedOwnersAreReturnedAsIs() {
        val owners = listOf(
            app(Isolation.Sandboxed(false, ProxyRoute.Via("proxy-a"))),
            app(Isolation.Sandboxed(false, ProxyRoute.Direct)),
            app(Isolation.Sandboxed(false, ProxyChoice.Inherit)),
            app(Isolation.Shared),
        )
        owners.forEach { assertSame(it, it.withExistingProxy(existing)) }
        val group = group(Isolation.Sandboxed(false, ProxyRoute.Via("proxy-a")))
        assertSame(group, group.withExistingProxy(existing))
    }

    @Test
    fun wholeStateDropsDeletedProxyAndKeepsTheRest() {
        val keptApp = app(Isolation.Sandboxed(false, ProxyRoute.Via("proxy-a")))
        val staleApp = app(Isolation.Sandboxed(false, ProxyRoute.Via("gone"))).copy(uuid = "stale")
        val staleGroup = group(Isolation.Sandboxed(false, ProxyRoute.Via("gone")))
        val state = DataState(
            webApps = listOf(keptApp, staleApp),
            groups = listOf(staleGroup),
            globalSettings = WebApp(baseUrl = "", uuid = "global", title = ""),
            proxies = listOf(Proxy(uuid = "proxy-a")),
        )

        val next = DataReducer.withExistingProxies(state)

        assertSame(keptApp, next.webApps[0])
        assertEquals(Isolation.Sandboxed(false, ProxyChoice.Inherit), next.webApps[1].isolation)
        assertEquals(Isolation.Sandboxed(false, ProxyRoute.Direct), next.groups[0].isolation)
        assertSame(state.proxies, next.proxies)
    }

    @Test
    fun sandboxHelpers() {
        val webApp = app(Isolation.Shared)
        assertSame(webApp, webApp.withEphemeral(true))
        assertSame(webApp, webApp.withProxy(ProxyRoute.Direct))
        assertEquals(Isolation.Sandboxed(false, ProxyChoice.Inherit), webApp.sandboxed().isolation)
        assertEquals(Isolation.Sandboxed(false, ProxyRoute.Direct), group(Isolation.Shared).sandboxed().isolation)
    }
}
