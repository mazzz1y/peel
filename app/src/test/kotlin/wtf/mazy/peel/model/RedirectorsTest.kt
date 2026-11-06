package wtf.mazy.peel.model

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectorsTest {

    private data class Case(val name: String, val link: String, val expected: UnwrappedLink)

    private val target = "https://tracker.example.com/projects/demo/boards/1"
    private val encodedTarget = encode(target)

    private fun encode(url: String): String = URLEncoder.encode(url, "UTF-8")

    private fun assertCases(cases: List<Case>) {
        val mismatches = cases.mapNotNull { case ->
            val actual = Redirectors.unwrap(case.link)
            "${case.name}: expected ${case.expected}, got $actual".takeIf { actual != case.expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun trackersAreSkipped() = assertCases(
        mapOf(
            "Google Chat" to "https://www.google.com/url?sa=j&url=$encodedTarget&uct=1&usg=sig&source=chat",
            "Google search result" to "https://www.google.com/url?q=$encodedTarget&sa=U",
            "Google compound TLD" to "https://www.google.co.uk/url?url=$encodedTarget",
            "Google country TLD" to "https://google.com.br/url?q=$encodedTarget",
            "YouTube" to "https://www.youtube.com/redirect?event=video_description&q=$encodedTarget",
            "Facebook" to "https://l.facebook.com/l.php?u=$encodedTarget&h=AT0abc",
            "Facebook mobile" to "https://lm.facebook.com/l.php?u=$encodedTarget",
            "Messenger" to "https://l.messenger.com/l.php?u=$encodedTarget&h=AT1",
            "Instagram" to "https://l.instagram.com/?u=$encodedTarget&e=AT2",
            "VK" to "https://vk.com/away.php?to=$encodedTarget&cc_key=",
            "LinkedIn" to "https://www.linkedin.com/redir/redirect?url=$encodedTarget&urlhash=x",
            "LinkedIn safety" to "https://www.linkedin.com/safety/go?url=$encodedTarget&trk=flagship",
            "Reddit" to "https://out.reddit.com/t3_abc?url=$encodedTarget&token=x",
            "DuckDuckGo" to "https://duckduckgo.com/l/?uddg=$encodedTarget&rut=abc",
            "Steam" to "https://steamcommunity.com/linkfilter/?url=$encodedTarget",
            "Steam without slash" to "https://steamcommunity.com/linkfilter?url=$encodedTarget",
            "Tumblr" to "https://t.umblr.com/redirect?z=$encodedTarget&t=abc",
            "Slack" to "https://slack-redir.net/link?url=$encodedTarget",
        ).map { (name, link) -> Case(name, link, UnwrappedLink(target)) },
    )

    @Test
    fun destinationIsRebuiltFaithfully() = assertCases(
        listOf(
            Case(
                "Google AMP path tail",
                "https://www.google.com/amp/s/example.org/news/story?id=7#part",
                UnwrappedLink("https://example.org/news/story?id=7#part"),
            ),
            Case(
                "plus sign kept",
                "https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fsearch%3Fq%3Dc++",
                UnwrappedLink("https://example.org/search?q=c++"),
            ),
        ),
    )

    @Test
    fun checksAreMatchedThroughButOpened() {
        val safeLinks = "https://eur02.safelinks.protection.outlook.com/?url=$encodedTarget&data=05%7C02&reserved=0"
        val checkAroundTracker = "https://nam12.safelinks.protection.outlook.com/?url=" +
            encode("https://www.google.com/url?q=$encodedTarget") + "&data=1"
        assertCases(
            listOf(
                Case("Safe Links", safeLinks, UnwrappedLink(target, safeLinks)),
                Case(
                    "tracker around check",
                    "https://www.google.com/url?q=${encode(safeLinks)}",
                    UnwrappedLink(target, safeLinks),
                ),
                Case("check around tracker", checkAroundTracker, UnwrappedLink(target, checkAroundTracker)),
            ),
        )
    }

    @Test
    fun otherLinksAreUntouched() = assertCases(
        mapOf(
            "Instagram outside its redirector" to "https://www.instagram.com/p/abc/?u=$encodedTarget",
            "sign-in return address" to
                "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmail.google.com%2F",
            "javascript value" to "https://www.google.com/url?q=javascript%3Aalert(1)",
            "relative value" to "https://l.facebook.com/l.php?u=%2Fsettings",
            "unknown host with url parameter" to "https://example.com/go?url=$encodedTarget",
            "shortener" to "https://t.co/abc123",
            "plain link" to target,
            "malformed link" to "https://exa mple.com/url?q=x",
            "malformed percent escape" to "https://vk.com/away.php?to=https%3A%2F%2Fexample.org%ZZ",
        ).map { (name, link) -> Case(name, link, UnwrappedLink(link)) },
    )

    @Test
    fun layersStopAtTheCap() {
        val layers = generateSequence(target) { "https://vk.com/away.php?to=${encode(it)}" }.take(6).toList()
        assertEquals(UnwrappedLink(layers[1]), Redirectors.unwrap(layers.last()))
    }
}
