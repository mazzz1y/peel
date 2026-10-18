package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DataStateTest {

    @Test
    fun ephemeralContextIdsAreTheOwnersOfEphemeralSandboxes() {
        val state = DataState(
            webApps = listOf(
                WebApp(baseUrl = "", uuid = "own", title = "", isolation = Isolation.Sandboxed(true, ProxyChoice.Inherit)),
                WebApp(baseUrl = "", uuid = "kept", title = "", isolation = Isolation.Sandboxed(false, ProxyChoice.Inherit)),
                WebApp(baseUrl = "", uuid = "member", title = "", isolation = Isolation.Shared, groupUuid = "work"),
            ),
            groups = listOf(
                WebAppGroup(uuid = "work", isolation = Isolation.Sandboxed(true, ProxyRoute.Direct)),
                WebAppGroup(uuid = "plain", isolation = Isolation.Shared),
            ),
            globalSettings = WebApp(baseUrl = "", uuid = "global", title = ""),
        )
        assertEquals(setOf("own", "work"), state.ephemeralContextIds)
    }
}
