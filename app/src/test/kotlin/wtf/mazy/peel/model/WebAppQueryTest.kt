package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppQueryTest {

    private val groupTitles = mapOf("work" to "Work Tools")

    private val gitLab = WebApp(
        baseUrl = "https://gitlab.example.org/team",
        title = "GitLab",
        groupUuid = "work",
    )

    @Test
    fun titleMatchesIgnoringCase() {
        assertTrue(gitLab.matchesQuery("lab", groupTitles))
        assertTrue(gitLab.matchesQuery("GITL", groupTitles))
    }

    @Test
    fun addressMatches() {
        assertTrue(gitLab.matchesQuery("example.org/te", groupTitles))
    }

    @Test
    fun groupTitleMatches() {
        assertTrue(gitLab.matchesQuery("tools", groupTitles))
    }

    @Test
    fun unrelatedQueryDoesNotMatch() {
        assertFalse(gitLab.matchesQuery("wiki", groupTitles))
    }

    @Test
    fun ungroupedAppIgnoresGroupTitles() {
        assertFalse(gitLab.copy(groupUuid = null).matchesQuery("tools", groupTitles))
    }

    @Test
    fun surroundingSpacesAreIgnored() {
        assertTrue(gitLab.matchesQuery("  gitl ", groupTitles))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(gitLab.matchesQuery("", groupTitles))
        assertTrue(gitLab.matchesQuery("  ", groupTitles))
    }

    @Test
    fun searchedKeepsMatchesInGivenOrder() {
        val wiki = WebApp(baseUrl = "https://wiki.example.com", title = "Wiki")
        val gitHub = WebApp(baseUrl = "https://github.example.com", title = "GitHub")
        val apps = listOf(wiki, gitLab, gitHub)
        val cases = listOf(
            "" to apps,
            "  " to apps,
            "git" to listOf(gitLab, gitHub),
            "tools" to listOf(gitLab),
            "zzqq" to emptyList(),
        )
        cases.forEach { (query, expected) ->
            assertEquals(query, expected, apps.searched(query, groupTitles, typedHost = null))
        }
    }
}
