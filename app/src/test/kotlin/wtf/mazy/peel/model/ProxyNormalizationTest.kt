package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProxyNormalizationTest {

    private val existing = setOf("tor")

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
            app(Isolation.Sandboxed(false, ProxyRoute.Via("tor"))),
            app(Isolation.Sandboxed(false, ProxyRoute.Direct)),
            app(Isolation.Sandboxed(false, ProxyChoice.Inherit)),
            app(Isolation.Shared),
        )
        owners.forEach { assertSame(it, it.withExistingProxy(existing)) }
        val group = group(Isolation.Sandboxed(false, ProxyRoute.Via("tor")))
        assertSame(group, group.withExistingProxy(existing))
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
