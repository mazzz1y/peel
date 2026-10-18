package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveSandboxTest {

    private val proxyA = ProxyRoute.Via("proxy-a")
    private val proxyB = ProxyRoute.Via("proxy-b")

    private fun app(isolation: Isolation<ProxyChoice>, isPrivate: Boolean = false) = WebApp(
        baseUrl = "https://example.com",
        uuid = "app",
        title = "app",
        isolation = isolation,
        isPrivateSession = isPrivate,
        groupUuid = "group",
    )

    private fun group(isolation: Isolation<ProxyRoute>) =
        WebAppGroup(uuid = "group", isolation = isolation)

    private val sandboxedProxiedGroup = group(Isolation.Sandboxed(ephemeral = true, proxy = proxyA))
    private val sharedGroup = group(Isolation.Shared)

    @Test
    fun privateSessionIsPrivateWhateverItsIsolation() {
        val webApp = app(Isolation.Sandboxed(false, proxyB), isPrivate = true)
        assertEquals(EffectiveSandbox.Private, effectiveSandbox(webApp, sandboxedProxiedGroup))
    }

    @Test
    fun sharedAppInSandboxedGroupUsesGroupContainerAndRoute() {
        assertEquals(
            EffectiveSandbox.Shared("group", ephemeral = true, route = proxyA),
            effectiveSandbox(app(Isolation.Shared), sandboxedProxiedGroup),
        )
    }

    @Test
    fun sharedAppWithoutSandboxedGroupIsDirect() {
        val expected = EffectiveSandbox.Shared(null, ephemeral = false, route = ProxyRoute.Direct)
        assertEquals(expected, effectiveSandbox(app(Isolation.Shared), sharedGroup))
        assertEquals(expected, effectiveSandbox(app(Isolation.Shared), null))
    }

    @Test
    fun explicitDirectIgnoresGroupProxy() {
        assertEquals(
            EffectiveSandbox.Own("app", false, ProxyRoute.Direct),
            effectiveSandbox(app(Isolation.Sandboxed(false, ProxyRoute.Direct)), sandboxedProxiedGroup),
        )
    }

    @Test
    fun namedProxyOverridesGroupProxy() {
        assertEquals(
            EffectiveSandbox.Own("app", false, proxyB),
            effectiveSandbox(app(Isolation.Sandboxed(false, proxyB)), sandboxedProxiedGroup),
        )
    }

    @Test
    fun inheritTakesSandboxedGroupRoute() {
        assertEquals(
            EffectiveSandbox.Own("app", false, proxyA),
            effectiveSandbox(app(Isolation.Sandboxed(false, ProxyChoice.Inherit)), sandboxedProxiedGroup),
        )
    }

    @Test
    fun inheritWithoutSandboxedGroupIsDirect() {
        val webApp = app(Isolation.Sandboxed(false, ProxyChoice.Inherit))
        val expected = EffectiveSandbox.Own("app", false, ProxyRoute.Direct)
        assertEquals(expected, effectiveSandbox(webApp, sharedGroup))
        assertEquals(expected, effectiveSandbox(webApp, null))
    }

    @Test
    fun ownSandboxIgnoresEphemeralGroup() {
        val sandbox = effectiveSandbox(app(Isolation.Sandboxed(false, ProxyChoice.Inherit)), sandboxedProxiedGroup)
        assertEquals(null, sandbox.ephemeralContextId)
    }

    @Test
    fun ephemeralOwnSandboxHasEphemeralContextId() {
        val sandbox = effectiveSandbox(app(Isolation.Sandboxed(true, ProxyChoice.Inherit)), null)
        assertEquals("app", sandbox.ephemeralContextId)
    }

    @Test
    fun sharedAppInEphemeralGroupWipesGroupContainer() {
        assertEquals("group", effectiveSandbox(app(Isolation.Shared), sandboxedProxiedGroup).ephemeralContextId)
    }

    @Test
    fun sharedAppInNonEphemeralGroupKeepsGroupContainer() {
        val keptGroup = group(Isolation.Sandboxed(ephemeral = false, proxy = proxyA))
        assertEquals(null, effectiveSandbox(app(Isolation.Shared), keptGroup).ephemeralContextId)
    }

    @Test
    fun groupResolution() {
        assertEquals(EffectiveSandbox.Own("group", true, proxyA), effectiveSandbox(sandboxedProxiedGroup))
        assertEquals(
            EffectiveSandbox.Shared(null, ephemeral = false, route = ProxyRoute.Direct),
            effectiveSandbox(sharedGroup),
        )
    }

    @Test
    fun contextIdPerKind() {
        assertEquals(null, EffectiveSandbox.Private.contextId)
        assertEquals("group", EffectiveSandbox.Shared("group", false, proxyA).contextId)
        assertEquals("app", EffectiveSandbox.Own("app", false, proxyA).contextId)
    }
}
