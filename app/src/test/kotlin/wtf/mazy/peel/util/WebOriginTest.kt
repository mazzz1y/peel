package wtf.mazy.peel.util

import org.junit.Assert.assertTrue
import org.junit.Test

class WebOriginTest {

    @Test
    fun parses() {
        val cases = listOf(
            "https://example.com" to WebOrigin("https", "example.com", 443),
            "https://example.com:443/path" to WebOrigin("https", "example.com", 443),
            "HTTP://Example.COM./x?y#z" to WebOrigin("http", "example.com", 80),
            "http://localhost:8899/" to WebOrigin("http", "localhost", 8899),
            "http://nas:5000" to WebOrigin("http", "nas", 5000),
            "http://my_nas.lan/" to WebOrigin("http", "my_nas.lan", 80),
            "https://user:pass@example.com:8443/" to WebOrigin("https", "example.com", 8443),
            "https://u:p@ss@example.com/" to WebOrigin("https", "example.com", 443),
            "https://example.com\\evil.example/" to WebOrigin("https", "example.com", 443),
            "https://example.com.:8443/" to WebOrigin("https", "example.com", 8443),
            "http://192.168.1.10:8080/a" to WebOrigin("http", "192.168.1.10", 8080),
            "http://[::1]:8080/" to WebOrigin("http", "[::1]", 8080),
            "http://[::1]/" to WebOrigin("http", "[::1]", 80),
            "http://[FE80::1]:8080/" to WebOrigin("http", "[fe80::1]", 8080),
            "https://пример.рф:8443/" to WebOrigin("https", "xn--e1afmkfd.xn--p1ai", 8443),
            "https://example.com/path with spaces|and pipes" to WebOrigin("https", "example.com", 443),
            "https://example.com:/" to WebOrigin("https", "example.com", 443),
            "ftp://example.com/" to null,
            "about:blank" to null,
            "example.com" to null,
            "https://" to null,
            "https:///path" to null,
            "https://exa mple.com/" to null,
            "https://example.com:99999/" to null,
            "https://example.com:port/" to null,
            "garbage" to null,
        )
        val mismatches = cases.mapNotNull { (url, expected) ->
            val actual = WebOrigin.of(url)
            "$url: expected $expected, got $actual".takeIf { actual != expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun comparesPlaces() {
        val cases = listOf(
            Triple("https://example.com", "https://www.example.com/x", true),
            Triple("https://www.example.com", "https://example.com", true),
            Triple("https://example.com", "https://example.com:443", true),
            Triple("http://nas:5000", "http://nas:5000/web", true),
            Triple("http://nas:5000", "http://nas:8096", false),
            Triple("http://example.com", "https://example.com", false),
            Triple("https://mail.example.com", "https://example.com", false),
            Triple("https://www2.example.com", "https://example.com", false),
        )
        val mismatches = cases.mapNotNull { (a, b, expected) ->
            val actual = WebOrigin.of(a)!!.isSamePlaceAs(WebOrigin.of(b)!!)
            "$a vs $b: expected $expected, got $actual".takeIf { actual != expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }
}
