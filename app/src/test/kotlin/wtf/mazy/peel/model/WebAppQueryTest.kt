package wtf.mazy.peel.model

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
}
