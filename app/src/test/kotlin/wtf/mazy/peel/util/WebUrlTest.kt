package wtf.mazy.peel.util

import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlTest {

    private fun assertCases(cases: List<Pair<String?, String?>>, check: (String?) -> String?) {
        val mismatches = cases.mapNotNull { (raw, expected) ->
            val actual = check(raw)
            "$raw: expected $expected, got $actual".takeIf { actual != expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun acceptsOnlyWebAddresses() = assertCases(
        listOf(
            "https://example.com/page" to "https://example.com/page",
            "  http://example.com  " to "http://example.com",
            "HTTPS://Example.com/" to "HTTPS://Example.com/",
            "http://192.168.1.10:8080/" to "http://192.168.1.10:8080/",
            "file:///data/data/wtf.mazy.peel/files/peel.db" to null,
            "content://wtf.mazy.peel.fileprovider/x" to null,
            "javascript:alert(1)" to null,
            "data:text/html,<b>x</b>" to null,
            "about:config" to null,
            "moz-extension://abc/page.html" to null,
            "intent://scan#Intent;scheme=zxing;end" to null,
            "https://" to null,
            "example.com" to null,
            "" to null,
            null to null,
        ),
        WebUrl::accept,
    )

    @Test
    fun findsTheLinkInSharedText() = assertCases(
        listOf(
            "https://example.com/page" to "https://example.com/page",
            "Look: https://example.com/page, nice" to "https://example.com/page",
            "https://example.com!" to "https://example.com",
            "'https://example.com/page'" to "https://example.com/page",
            "\"https://example.com/a?b=c\"" to "https://example.com/a?b=c",
            "(see https://example.com/wiki/Kotlin_(language))" to "https://example.com/wiki/Kotlin_(language)",
            "<https://example.com/x>" to "https://example.com/x",
            "Title\nhttps://example.com/x/\nmore" to "https://example.com/x/",
            "HTTPS://EXAMPLE.COM/A." to "HTTPS://EXAMPLE.COM/A",
            "no link here" to null,
            "javascript:alert(1) https://" to null,
        ),
        { it?.let(WebUrl::find) },
    )
}
