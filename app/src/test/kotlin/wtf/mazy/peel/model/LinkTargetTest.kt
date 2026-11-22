package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkTargetTest {

    @Test
    fun addressIsShownWholeExceptInlineData() {
        val cases = listOf(
            "https://blog.example.com/kotlin-2-4" to "https://blog.example.com/kotlin-2-4",
            "http://www.example.com/" to "http://www.example.com/",
            "https://example.com/a?ref=1#top" to "https://example.com/a?ref=1#top",
            "blob:https://example.com/0f3a" to "blob:https://example.com/0f3a",
            "data:image/png;base64,AAAA" to "image/png",
            "DATA:image/gif,AAAA" to "image/gif",
            "data:,hello" to "data:",
        )
        cases.forEach { (address, shown) -> assertEquals(address, shown, displayAddress(address)) }
    }

    @Test
    fun titleIsDroppedWhenItOnlyRepeatsTheAddress() {
        val url = "https://news.example.com/a"
        val cases = listOf(
            "Story" to "Story",
            "  Story " to "Story",
            "" to null,
            "   " to null,
            url to null,
            "news.example.com/a" to null,
            "www.news.example.com/a/" to null,
            "WWW.News.Example.com/a" to null,
            "news.example.com" to "news.example.com",
            null to null,
        )
        cases.forEach { (text, title) ->
            assertEquals("$text", title, LinkTarget(UnwrappedLink(url), url, text).title)
        }
    }

    @Test
    fun targetFollowsItsSubjectAndEdits() {
        val link = UnwrappedLink("https://news.example.com/a")
        val edited = UnwrappedLink("https://news.example.com/b")
        val image = "https://cdn.example.com/p.jpg"
        val inline = "data:image/png;base64,AAAA"
        data class Case(val target: LinkTarget, val title: String?, val shareable: String?, val link: UnwrappedLink?)
        val cases = listOf(
            Case(LinkTarget.of(LinkSubject.Web(link, "Story", image)), "Story", link.destination, link),
            Case(LinkTarget.of(LinkSubject.Web(link, "Story")).edited(edited), null, edited.destination, edited),
            Case(LinkTarget.of(LinkSubject.Media(MediaKind.IMAGE, image, "Sunset")), "Sunset", image, null),
            Case(LinkTarget.of(LinkSubject.Media(MediaKind.IMAGE, inline, null)), null, null, null),
        )
        cases.forEach { case ->
            assertEquals(case.toString(), case.title, case.target.title)
            assertEquals(case.toString(), case.shareable, case.target.shareable)
            assertEquals(case.toString(), case.link, case.target.link)
        }
    }
}
