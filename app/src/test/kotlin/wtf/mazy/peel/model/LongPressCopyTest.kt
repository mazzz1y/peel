package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LongPressCopyTest {

    @Test
    fun copiesWhatTheLinkPointsAt() {
        val cases = listOf(
            "mailto:me@example.com" to "me@example.com",
            "mailto:me@example.com?subject=Hi" to "me@example.com",
            "MAILTO:me%40example.com" to "me@example.com",
            "mailto:a@example.com,b@example.com" to "a@example.com,b@example.com",
            "tel:+1-555-0100" to "+1-555-0100",
            "tel:%2B1%20555%200100" to "+1 555 0100",
            "sms:+15550100?body=Hello" to "+15550100",
            "smsto:+15550100" to "+15550100",
            "intent://scan/#Intent;scheme=zxing;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fscan;end" to
                    "https://example.com/scan",
            "intent://scan/#Intent;scheme=zxing;package=com.example.scan;end" to
                    "intent://scan/#Intent;scheme=zxing;package=com.example.scan;end",
            "mailto:" to "mailto:",
            "tel:%E0%A4%A" to "tel:%E0%A4%A",
            "geo:52.5,13.4" to "geo:52.5,13.4",
            "market://details?id=com.example.app" to "market://details?id=com.example.app",
        )
        cases.forEach { (uri, copied) -> assertEquals(uri, copied, LongPressCopy.of(uri)) }
    }
}
