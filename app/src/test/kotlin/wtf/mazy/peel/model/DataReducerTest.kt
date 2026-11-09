package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DataReducerTest {

    private fun app(uuid: String, group: String?, order: Int) =
        WebApp(baseUrl = "https://example.com/$uuid", uuid = uuid, title = uuid, groupUuid = group, order = order)

    private fun state(vararg webApps: WebApp) = DataState(
        webApps = webApps.toList(),
        groups = listOf(WebAppGroup(uuid = "work"), WebAppGroup(uuid = "home")),
        globalSettings = WebApp(baseUrl = "", uuid = "global", title = ""),
    )

    private fun DataState.positions(): Map<String, Pair<String?, Int>> =
        webApps.associate { it.uuid to (it.groupUuid to it.order) }

    @Test
    fun movingAppendsAfterTheDestinationInRelativeOrder() {
        val cases = listOf(
            Triple(
                state(app("a", "work", 0), app("b", "work", 1), app("c", null, 0), app("d", null, 1)),
                setOf("b", "a") to null,
                mapOf("a" to (null to 2), "b" to (null to 3), "c" to (null to 0), "d" to (null to 1)),
            ),
            Triple(
                state(app("a", "work", 5), app("b", "home", 0)),
                setOf("a") to "home",
                mapOf("a" to ("home" to 1), "b" to ("home" to 0)),
            ),
            Triple(
                state(app("a", null, 3), app("b", null, 1)),
                setOf("a", "b") to "work",
                mapOf("a" to ("work" to 1), "b" to ("work" to 0)),
            ),
        )
        cases.forEachIndexed { i, (before, move, expected) ->
            val after = DataReducer.movingWebAppsToGroup(before, move.first, move.second)
            assertEquals("case $i", expected, after.positions())
        }
    }

    @Test
    fun orderAllocatorAppendsPerGroup() {
        val current = state(app("a", "work", 0), app("b", "work", 4), app("c", null, 2))
        val allocator = OrderAllocator(current)
        val cases = listOf("work" to 5, "work" to 6, null to 3, "home" to 0)
        cases.forEach { (group, expected) ->
            assertEquals("group $group", expected, allocator.nextWebAppOrder(group))
        }
    }
}
