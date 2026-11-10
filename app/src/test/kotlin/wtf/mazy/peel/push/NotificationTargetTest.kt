package wtf.mazy.peel.push

import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.mazy.peel.model.EffectiveSandbox
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.model.WebApp

class NotificationTargetTest {

    private sealed interface Box {
        data object None : Box
        data object Own : Box
        data class Group(val id: String) : Box
        data object Private : Box
    }

    private data class App(val uuid: String, val url: String, val box: Box, val alwaysHttps: Boolean = false)

    private data class Case(
        val name: String,
        val apps: List<App>,
        val contextId: String?,
        val url: String,
        val expected: String?,
        val heuristic: String? = null,
    )

    private fun sandbox(app: App): EffectiveSandbox = when (val box = app.box) {
        Box.None -> EffectiveSandbox.Shared(null, ephemeral = false, route = ProxyRoute.Direct)
        Box.Own -> EffectiveSandbox.Own(app.uuid, ephemeral = false, route = ProxyRoute.Direct)
        is Box.Group -> EffectiveSandbox.Shared(box.id, ephemeral = false, route = ProxyRoute.Direct)
        Box.Private -> EffectiveSandbox.Private
    }

    private fun resolve(case: Case): String? {
        val byUuid = case.apps.associateBy { it.uuid }
        val webApps = case.apps.map { WebApp(baseUrl = it.url, uuid = it.uuid, title = it.uuid) }
        return NotificationTarget.resolve(
            contextId = case.contextId,
            url = case.url,
            apps = webApps,
            sandboxOf = { sandbox(byUuid.getValue(it.uuid)) },
            addressOf = { app ->
                val own = byUuid.getValue(app.uuid)
                if (own.alwaysHttps) own.url.replaceFirst("http://", "https://") else own.url
            },
            heuristic = { candidates, _ -> candidates.find { it.uuid == case.heuristic } },
        )?.uuid
    }

    @Test
    fun resolves() {
        val cases = listOf(
            Case(
                "own sandbox on a single-name host",
                listOf(App("a", "http://localhost:8899/", Box.Own), App("b", "http://localhost:8899/", Box.None)),
                contextId = "a", url = "http://localhost:8899", expected = "a",
            ),
            Case(
                "own sandbox, site on another host",
                listOf(App("a", "https://example.com/", Box.Own)),
                contextId = "a", url = "https://other.example.net", expected = "a",
            ),
            Case(
                "ports tell apart two apps without a sandbox",
                listOf(App("a", "http://nas.local:5000/", Box.None), App("b", "http://nas.local:8096/", Box.None)),
                contextId = null, url = "http://nas.local:8096", expected = "b", heuristic = "a",
            ),
            Case(
                "single-name host without a sandbox",
                listOf(App("a", "http://nas:5000/", Box.None)),
                contextId = null, url = "http://nas:5000", expected = "a",
            ),
            Case(
                "group sandbox picks by port",
                listOf(App("a", "http://localhost:8899/", Box.Group("g")), App("b", "http://localhost:8898/", Box.Group("g"))),
                contextId = "g", url = "http://localhost:8898", expected = "b",
            ),
            Case(
                "www and HTTPS upgrade are the same place",
                listOf(App("a", "http://www.example.com/", Box.None, alwaysHttps = true), App("b", "https://mail.example.com/", Box.None)),
                contextId = null, url = "https://example.com", expected = "a", heuristic = "b",
            ),
            Case(
                "default port is the same place",
                listOf(App("a", "https://example.com:443/app", Box.None)),
                contextId = null, url = "https://example.com", expected = "a",
            ),
            Case(
                "best match within the sandbox",
                listOf(App("a", "https://mail.example.com/", Box.None)),
                contextId = null, url = "https://example.com", expected = "a", heuristic = "a",
            ),
            Case(
                "no match",
                listOf(App("a", "https://mail.example.com/", Box.None)),
                contextId = null, url = "https://example.com", expected = null,
            ),
            Case(
                "an app's own sandbox is never guessed for a site outside it",
                listOf(App("a", "https://example.com/", Box.Own)),
                contextId = null, url = "https://example.com", expected = null, heuristic = "a",
            ),
            Case(
                "another group's app is never chosen",
                listOf(App("a", "http://localhost:8899/", Box.Group("g1"))),
                contextId = "g2", url = "http://localhost:8899", expected = null, heuristic = "a",
            ),
            Case(
                "private app is never chosen",
                listOf(App("a", "https://example.com/", Box.Private)),
                contextId = null, url = "https://example.com", expected = null, heuristic = "a",
            ),
            Case(
                "two apps at one place fall back to the heuristic",
                listOf(App("a", "https://example.com/x", Box.None), App("b", "https://example.com/y", Box.None)),
                contextId = null, url = "https://example.com", expected = "b", heuristic = "b",
            ),
        )
        val mismatches = cases.mapNotNull { case ->
            val actual = resolve(case)
            "${case.name}: expected ${case.expected}, got $actual".takeIf { actual != case.expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }
}
