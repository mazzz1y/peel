package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSheetRowsTest {

    private val gitHub = WebApp(baseUrl = "https://github.com", title = "GitHub")
    private val gitLab = WebApp(baseUrl = "https://gitlab.com", title = "GitLab", groupUuid = "work")
    private val news = WebApp(baseUrl = "https://news.example.com", title = "News")
    private val all = listOf(gitHub, gitLab, news)
    private val groupTitles = mapOf("work" to "Work")

    @Test
    fun candidatesLeaveOutTheCurrentWindowsWebApp() {
        val cases = listOf(
            LinkOrigin.Outside { true } to all,
            LinkOrigin.Outside { it.groupUuid == "work" } to listOf(gitLab),
            LinkOrigin.Outside { false } to all,
            LinkOrigin.Page(gitHub.uuid) to listOf(gitLab, news),
            LinkOrigin.Page(news.uuid) to listOf(gitHub, gitLab),
            LinkOrigin.Page(null) to all,
            LinkOrigin.Window.App(gitHub) to listOf(gitLab, news),
            LinkOrigin.Window.App(WebApp(baseUrl = "https://example.com", title = "Transient")) to all,
            LinkOrigin.Window.PrivateSession to all,
            LinkOrigin.Window.Extension("Options") to all,
        )
        cases.forEach { (origin, expected) ->
            assertEquals(origin.toString(), expected, LinkSheetRows.candidates(all, origin))
        }
    }

    @Test
    fun aSheetWithNowhereToOpenTheLinkHasNoList() {
        val cases = listOf(
            Triple(listOf(gitHub), LinkOrigin.Page(gitHub.uuid), false),
            Triple(listOf(gitHub), LinkOrigin.Page(null), true),
            Triple(listOf(gitHub), LinkOrigin.Window.App(gitHub), true),
            Triple(emptyList(), LinkOrigin.Window.PrivateSession, true),
            Triple(emptyList(), LinkOrigin.Outside { true }, false),
        )
        cases.forEach { (stored, origin, listed) ->
            val candidates = LinkSheetRows.candidates(stored, origin)
            assertEquals(origin.toString(), listed, LinkSheetRows.hasRows(candidates, origin))
        }
    }

    @Test
    fun theCurrentWindowLeadsAndFollowsItsWebAppInSearch() {
        val others = listOf(gitLab, news)
        val window = LinkSheetRow.CurrentWindow(LinkOrigin.Window.App(gitHub))
        val privateWindow = LinkSheetRow.CurrentWindow(LinkOrigin.Window.PrivateSession)
        val apps = { list: List<WebApp> -> list.map(LinkSheetRow::App) }
        data class Case(val origin: LinkOrigin, val query: String, val expected: List<LinkSheetRow>)
        val cases = listOf(
            Case(LinkOrigin.Outside { true }, "", apps(all)),
            Case(LinkOrigin.Outside { true }, "lab", apps(listOf(gitLab))),
            Case(LinkOrigin.Page(gitHub.uuid), "", apps(others)),
            Case(LinkOrigin.Window.App(gitHub), "", listOf(window) + apps(others)),
            Case(LinkOrigin.Window.App(gitHub), "git", listOf(window) + apps(listOf(gitLab))),
            Case(LinkOrigin.Window.App(gitHub), "lab", apps(listOf(gitLab))),
            Case(LinkOrigin.Window.App(gitHub), "github.com", listOf(window)),
            Case(LinkOrigin.Window.App(gitHub), "zzz", emptyList()),
            Case(LinkOrigin.Window.App(gitHub), "work", apps(listOf(gitLab))),
            Case(LinkOrigin.Window.PrivateSession, "", listOf(privateWindow) + apps(all)),
            Case(LinkOrigin.Window.PrivateSession, "news", apps(listOf(news))),
        )
        cases.forEach { case ->
            val ordered = LinkSheetRows.candidates(all, case.origin)
            assertEquals(
                "${case.origin} '${case.query}'",
                case.expected,
                LinkSheetRows.rows(ordered, case.origin, case.query, groupTitles),
            )
        }
    }

    @Test
    fun theRestingSheetShowsTheWindowAndUpToThreeRelatedWebApps() {
        val docs = WebApp(baseUrl = "https://docs.example.com", title = "Docs")
        val mail = WebApp(baseUrl = "https://mail.example.com", title = "Mail")
        val wiki = WebApp(baseUrl = "https://wiki.example.com", title = "Wiki")
        val exampleSites = setOf(news, docs, mail, wiki)
        val window = LinkSheetRow.CurrentWindow(LinkOrigin.Window.PrivateSession)
        val apps = { list: List<WebApp> -> list.map(LinkSheetRow::App) }
        data class Case(val name: String, val rows: List<LinkSheetRow>, val expected: Int)
        val cases = listOf(
            Case("none related", apps(listOf(gitHub, gitLab)), 0),
            Case("one related, best first", apps(listOf(news, gitHub, gitLab)), 1),
            Case("all related", apps(listOf(news, docs)), 2),
            Case("at most three", apps(listOf(news, docs, mail, wiki, gitHub)), 3),
            Case("the window always rests", listOf(window) + apps(listOf(gitHub)), 1),
            Case("the window and related", listOf(window) + apps(listOf(news, docs, gitHub)), 3),
            Case("the window and three", listOf(window) + apps(listOf(news, docs, mail, wiki)), 4),
            Case("related after unrelated is not reached", apps(listOf(gitHub, news)), 0),
            Case("no rows", emptyList(), 0),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, LinkSheetRows.restingCount(case.rows, exampleSites::contains))
        }
    }
}
