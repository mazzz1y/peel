package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveSandboxTest {

    private val tor = ProxyRoute.Via("tor")
    private val vpn = ProxyRoute.Via("vpn")

    private fun app(isolation: Isolation<ProxyChoice>, private: Boolean = false) = WebApp(
        baseUrl = "https://example.com",
        uuid = "app",
        title = "app",
        isolation = isolation,
        isPrivateSession = private,
        groupUuid = "group",
    )

    private fun group(isolation: Isolation<ProxyRoute>) =
        WebAppGroup(uuid = "group", isolation = isolation)

    private val sandboxedTorGroup = group(Isolation.Sandboxed(ephemeral = true, proxy = tor))
    private val sharedGroup = group(Isolation.Shared)

    @Test
    fun privateSessionIsPrivateWhateverItsIsolation() {
        val webApp = app(Isolation.Sandboxed(false, vpn), private = true)
        assertEquals(EffectiveSandbox.Private, effectiveSandbox(webApp, sandboxedTorGroup))
    }

    @Test
    fun sharedAppInSandboxedGroupUsesGroupContainerAndRoute() {
        assertEquals(
            EffectiveSandbox.Shared("group", tor),
            effectiveSandbox(app(Isolation.Shared), sandboxedTorGroup),
        )
    }

    @Test
    fun sharedAppWithoutSandboxedGroupIsDirect() {
        val expected = EffectiveSandbox.Shared(null, ProxyRoute.Direct)
        assertEquals(expected, effectiveSandbox(app(Isolation.Shared), sharedGroup))
        assertEquals(expected, effectiveSandbox(app(Isolation.Shared), null))
    }

    @Test
    fun explicitDirectIgnoresGroupProxy() {
        assertEquals(
            EffectiveSandbox.Own("app", false, ProxyRoute.Direct),
            effectiveSandbox(app(Isolation.Sandboxed(false, ProxyRoute.Direct)), sandboxedTorGroup),
        )
    }

    @Test
    fun namedProxyOverridesGroupProxy() {
        assertEquals(
            EffectiveSandbox.Own("app", false, vpn),
            effectiveSandbox(app(Isolation.Sandboxed(false, vpn)), sandboxedTorGroup),
        )
    }

    @Test
    fun inheritTakesSandboxedGroupRoute() {
        assertEquals(
            EffectiveSandbox.Own("app", false, tor),
            effectiveSandbox(app(Isolation.Sandboxed(false, ProxyChoice.Inherit)), sandboxedTorGroup),
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
        val sandbox = effectiveSandbox(app(Isolation.Sandboxed(false, ProxyChoice.Inherit)), sandboxedTorGroup)
        assertEquals(null, sandbox.ephemeralContextId)
    }

    @Test
    fun ephemeralOwnSandboxHasEphemeralContextId() {
        val sandbox = effectiveSandbox(app(Isolation.Sandboxed(true, ProxyChoice.Inherit)), null)
        assertEquals("app", sandbox.ephemeralContextId)
    }

    @Test
    fun groupResolution() {
        assertEquals(EffectiveSandbox.Own("group", true, tor), effectiveSandbox(sandboxedTorGroup))
        assertEquals(EffectiveSandbox.Shared(null, ProxyRoute.Direct), effectiveSandbox(sharedGroup))
    }

    @Test
    fun contextIdPerKind() {
        assertEquals(null, EffectiveSandbox.Private.contextId)
        assertEquals("group", EffectiveSandbox.Shared("group", tor).contextId)
        assertEquals("app", EffectiveSandbox.Own("app", false, tor).contextId)
    }
}
