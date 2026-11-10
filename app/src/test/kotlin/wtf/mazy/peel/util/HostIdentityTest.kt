package wtf.mazy.peel.util

import org.junit.Assert.assertTrue
import org.junit.Test

class HostIdentityTest {

    @Test
    fun splitsHosts() {
        val cases = listOf(
            "example.com" to HostIdentity("example", "com", emptyList()),
            "www.example.com" to HostIdentity("example", "com", emptyList()),
            "mail.google.com" to HostIdentity("google", "com", listOf("mail")),
            "a.b.example.co.uk" to HostIdentity("example", "co.uk", listOf("a", "b")),
            "service.gov.uk" to HostIdentity("service", "gov.uk", emptyList()),
            "co.uk" to HostIdentity("co", "uk", emptyList()),
            "localhost" to HostIdentity("localhost", "", emptyList()),
            "nas" to HostIdentity("nas", "", emptyList()),
            "192.168.1.10" to HostIdentity("192.168.1.10", "", emptyList()),
            "[::1]" to HostIdentity("[::1]", "", emptyList()),
            "" to null,
        )
        val mismatches = cases.mapNotNull { (host, expected) ->
            val actual = HostIdentity.of(host)
            "$host: expected $expected, got $actual".takeIf { actual != expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }
}
