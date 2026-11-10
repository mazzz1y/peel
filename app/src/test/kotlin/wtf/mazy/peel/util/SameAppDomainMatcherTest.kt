package wtf.mazy.peel.util

import org.junit.Assert.assertTrue
import org.junit.Test

class SameAppDomainMatcherTest {

    @Test
    fun matchesRules() {
        val cases = listOf(
            Triple("https://sub.example.com/x", "example.com", true),
            Triple("https://www.example.com/", "example.com", true),
            Triple("https://notexample.com/", "example.com", false),
            Triple("https://EXAMPLE.com./", "example.com", true),
            Triple("https://пример.рф/", "пример.рф", true),
            Triple("https://portal.example.com/support/1", "url:portal.example.com/support", true),
            Triple("https://portal.example.com/supportx", "url:portal.example.com/support", false),
            Triple("https://portal.example.com/a%20b/c", "url:portal.example.com/a b", true),
            Triple("https://example.com:8443/x", "re:https://example\\.com:8443/.*", true),
            Triple("https://example.com/x?y=1", "re:.*\\?y=1", true),
            Triple("https://www.google.com/", "host-re:(.*\\.)?google\\.com", true),
            Triple("intent://blocked.example/x#Intent;end", "blocked.example", true),
            Triple("ftp://files.blocked.example/", "blocked.example", true),
            Triple("about:blank", "blank", false),
            Triple("https://example.com/", "re:(", false),
        )
        val mismatches = cases.mapNotNull { (url, entry, expected) ->
            val actual = SameAppDomainMatcher.matches(url, listOf(entry))
            "$url ~ $entry: expected $expected, got $actual".takeIf { actual != expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }
}
