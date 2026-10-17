package wtf.mazy.peel.model.db

import org.junit.Assert.assertEquals
import org.junit.Test
import wtf.mazy.peel.model.Isolation
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup

class IsolationStorageMappingTest {

    private fun appEntity(container: Boolean, ephemeral: Boolean, proxy: String?) = WebAppEntity(
        uuid = "app",
        baseUrl = "https://example.com",
        title = "app",
        isUseContainer = container,
        isEphemeralSandbox = ephemeral,
        order = 0,
        proxyUuid = proxy,
    )

    private fun groupEntity(container: Boolean, ephemeral: Boolean, proxy: String?) =
        WebAppGroupEntity(
            uuid = "group",
            title = "group",
            isUseContainer = container,
            isEphemeralSandbox = ephemeral,
            proxyUuid = proxy,
        )

    @Test
    fun webAppStoredCombinations() {
        assertEquals(Isolation.Shared, appEntity(false, true, "tor").toDomain().isolation)
        assertEquals(
            Isolation.Sandboxed(true, ProxyChoice.Inherit),
            appEntity(true, true, null).toDomain().isolation,
        )
        assertEquals(
            Isolation.Sandboxed(false, ProxyRoute.Direct),
            appEntity(true, false, "direct").toDomain().isolation,
        )
        assertEquals(
            Isolation.Sandboxed(false, ProxyRoute.Via("tor")),
            appEntity(true, false, "tor").toDomain().isolation,
        )
    }

    @Test
    fun groupStoredCombinations() {
        assertEquals(Isolation.Shared, groupEntity(false, true, "tor").toDomain().isolation)
        assertEquals(
            Isolation.Sandboxed(true, ProxyRoute.Direct),
            groupEntity(true, true, null).toDomain().isolation,
        )
        assertEquals(
            Isolation.Sandboxed(false, ProxyRoute.Via("tor")),
            groupEntity(true, false, "tor").toDomain().isolation,
        )
    }

    @Test
    fun webAppWritesKeepStoredMeaning() {
        val shared = webApp(Isolation.Shared).toEntity()
        assertEquals(Triple(false, false, null), shared.stored())
        assertEquals(null, webApp(Isolation.Sandboxed(false, ProxyChoice.Inherit)).toEntity().proxyUuid)
        assertEquals("direct", webApp(Isolation.Sandboxed(false, ProxyRoute.Direct)).toEntity().proxyUuid)
    }

    @Test
    fun groupDirectIsStoredAsNull() {
        val group = WebAppGroup(uuid = "group", isolation = Isolation.Sandboxed(false, ProxyRoute.Direct))
        assertEquals(null, group.toEntity().proxyUuid)
    }

    @Test
    fun webAppRoundTrips() {
        webAppIsolations.forEach { isolation ->
            val webApp = webApp(isolation)
            assertEquals(isolation, webApp.toEntity().toDomain().isolation)
            assertEquals(isolation, webApp.toSurrogate().toDomain().isolation)
        }
    }

    @Test
    fun groupRoundTrips() {
        groupIsolations.forEach { isolation ->
            val group = WebAppGroup(uuid = "group", isolation = isolation)
            assertEquals(isolation, group.toEntity().toDomain().isolation)
            assertEquals(isolation, group.toSurrogate().toDomain().isolation)
        }
    }

    private fun webApp(isolation: Isolation<ProxyChoice>) =
        WebApp(baseUrl = "https://example.com", uuid = "app", title = "app", isolation = isolation)

    private fun WebAppEntity.stored() = Triple(isUseContainer, isEphemeralSandbox, proxyUuid)

    private val groupIsolations: List<Isolation<ProxyRoute>> = listOf(
        Isolation.Shared,
        Isolation.Sandboxed(false, ProxyRoute.Direct),
        Isolation.Sandboxed(true, ProxyRoute.Direct),
        Isolation.Sandboxed(false, ProxyRoute.Via("tor")),
        Isolation.Sandboxed(true, ProxyRoute.Via("tor")),
    )

    private val webAppIsolations: List<Isolation<ProxyChoice>> = groupIsolations + listOf(
        Isolation.Sandboxed(false, ProxyChoice.Inherit),
        Isolation.Sandboxed(true, ProxyChoice.Inherit),
    )
}
