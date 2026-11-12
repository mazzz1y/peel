package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkPromptOutcomeTest {

    private val url = "https://accounts.example.com/signin"
    private val edited = "https://accounts.example.com/signin?trimmed"
    private val app = WebApp(baseUrl = "https://example.com", title = "Example")

    private data class Case(val choice: LinkChoice, val mustReload: Boolean, val expected: LinkPromptOutcome)

    @Test
    fun eachChoiceSettlesThePromptedNavigation() {
        val elsewhere = listOf(
            LinkChoice.App(app, url),
            LinkChoice.System(url),
            LinkChoice.Incognito(url),
            LinkChoice.Share(url, "Sign in"),
            LinkChoice.Add(url),
        )
        val cases = listOf(
            Case(LinkChoice.CurrentWindow(url), mustReload = false, LinkPromptOutcome.Proceed),
            Case(LinkChoice.CurrentWindow(url), mustReload = true, LinkPromptOutcome.LoadHere(url)),
            Case(LinkChoice.CurrentWindow(edited), mustReload = false, LinkPromptOutcome.LoadHere(edited)),
            Case(LinkChoice.Dismissed, mustReload = false, LinkPromptOutcome.Decline(null)),
        ) + elsewhere.map { Case(it, mustReload = true, LinkPromptOutcome.Decline(it)) }
        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, LinkPromptOutcome.of(url, case.mustReload, case.choice))
        }
    }

    @Test
    fun onlyAnotherWebAppKeepsAWindowWithNothingToReturnTo() {
        val cases = listOf(
            LinkChoice.App(app, url) to false,
            LinkChoice.System(url) to true,
            LinkChoice.Incognito(url) to true,
            LinkChoice.Share(url, null) to true,
            LinkChoice.Add(url) to true,
            null to true,
        )
        cases.forEach { (elsewhere, abandons) ->
            assertEquals("$elsewhere", abandons, LinkPromptOutcome.Decline(elsewhere).abandonsWindow)
        }
    }

    @Test
    fun onlyTheSystemAndPrivateModeLeavePeel() {
        val cases = listOf(
            LinkChoice.App(app, url) to false,
            LinkChoice.System(url) to true,
            LinkChoice.Incognito(url) to true,
            LinkChoice.Share(url, null) to false,
            LinkChoice.Add(url) to false,
            null to false,
        )
        cases.forEach { (elsewhere, leaves) ->
            assertEquals("$elsewhere", leaves, LinkPromptOutcome.Decline(elsewhere).leavesPeel)
        }
    }
}
