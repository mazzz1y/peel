package wtf.mazy.peel.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingUrlTest {

    @Test
    fun acceptsOnlyWebAddresses() {
        val cases = listOf(
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
        )
        cases.forEach { (raw, expected) -> assertEquals(raw.toString(), expected, IncomingUrl.accept(raw)) }
    }
}
