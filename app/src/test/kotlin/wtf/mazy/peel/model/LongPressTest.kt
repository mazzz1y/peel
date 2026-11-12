package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LongPressTest {

    private data class Case(
        val name: String,
        val linkUri: String? = null,
        val linkText: String? = null,
        val altText: String? = null,
        val title: String? = null,
        val media: MediaKind? = null,
        val srcUri: String? = null,
        val expected: LongPress?,
    )

    @Test
    fun aPressedElementBecomesASheetACopyOrNothing() {
        val link = "https://news.example.com/a"
        val image = "https://cdn.example.com/p.jpg"
        val cases = listOf(
            Case(
                "link with text", linkUri = link, linkText = "  Story  ",
                expected = LongPress.Sheet(LinkSubject.Web(UnwrappedLink(link), "Story", null)),
            ),
            Case(
                "link without text falls back to title", linkUri = link, linkText = " ", title = "Tip",
                expected = LongPress.Sheet(LinkSubject.Web(UnwrappedLink(link), "Tip", null)),
            ),
            Case(
                "image that is a link", linkUri = link, altText = "Sunset",
                media = MediaKind.IMAGE, srcUri = image,
                expected = LongPress.Sheet(LinkSubject.Web(UnwrappedLink(link), "Sunset", image)),
            ),
            Case(
                "video that is a link keeps no media", linkUri = link,
                media = MediaKind.VIDEO, srcUri = "https://cdn.example.com/v.mp4",
                expected = LongPress.Sheet(LinkSubject.Web(UnwrappedLink(link), null, null)),
            ),
            Case(
                "image without a link", altText = "Sunset", media = MediaKind.IMAGE, srcUri = image,
                expected = LongPress.Sheet(LinkSubject.Media(MediaKind.IMAGE, image, "Sunset")),
            ),
            Case(
                "audio without a link", media = MediaKind.AUDIO, srcUri = "https://cdn.example.com/a.mp3",
                expected = LongPress.Sheet(LinkSubject.Media(MediaKind.AUDIO, "https://cdn.example.com/a.mp3", null)),
            ),
            Case("e-mail link", linkUri = "mailto:me@example.com", expected = LongPress.Copy("me@example.com")),
            Case("phone link over an image", linkUri = "tel:+1-555-0100", media = MediaKind.IMAGE, srcUri = image,
                expected = LongPress.Copy("+1-555-0100")),
            Case("script link over an image", linkUri = "javascript:void(0)", media = MediaKind.IMAGE, srcUri = image,
                expected = LongPress.Sheet(LinkSubject.Media(MediaKind.IMAGE, image, null))),
            Case("script link alone", linkUri = "javascript:void(0)", expected = null),
            Case("plain text", expected = null),
            Case("media without a source", media = MediaKind.VIDEO, expected = null),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LongPress.of(case.linkUri, case.linkText, case.altText, case.title, case.media, case.srcUri),
            )
        }
    }
}
