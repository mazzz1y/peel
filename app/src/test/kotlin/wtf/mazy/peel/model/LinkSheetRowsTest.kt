package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSheetRowsTest {

    private val gitHub = WebApp(baseUrl = "https://github.com", title = "GitHub")
    private val gitLab = WebApp(baseUrl = "https://gitlab.com", title = "GitLab", groupUuid = "work")
    private val news = WebApp(baseUrl = "https://news.example.com", title = "News")
    private val all = listOf(gitHub, gitLab, news)

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
            LinkOrigin.Window.Extension to all,
        )
        cases.forEach { (origin, expected) ->
            assertEquals(origin.toString(), expected, LinkSheetRows.candidates(all, origin))
        }
    }

    @Test
    fun theRestingSheetShowsUpToThreeRelatedWebApps() {
        val docs = WebApp(baseUrl = "https://docs.example.com", title = "Docs")
        val mail = WebApp(baseUrl = "https://mail.example.com", title = "Mail")
        val wiki = WebApp(baseUrl = "https://wiki.example.com", title = "Wiki")
        val exampleSites = setOf(news, docs, mail, wiki)
        data class Case(val name: String, val rows: List<WebApp>, val expected: Int)
        val cases = listOf(
            Case("none related", listOf(gitHub, gitLab), 0),
            Case("one related, best first", listOf(news, gitHub, gitLab), 1),
            Case("all related", listOf(news, docs), 2),
            Case("at most three", listOf(news, docs, mail, wiki, gitHub), 3),
            Case("related after unrelated is not reached", listOf(gitHub, news), 0),
            Case("no rows", emptyList(), 0),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, LinkSheetRows.restingCount(case.rows, exampleSites::contains))
        }
    }
}
