package wtf.mazy.peel.util

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Parsing and canonicalisation for user-supplied trust anchors.
 *
 * Users paste whatever their infrastructure handed them: a single PEM, a
 * concatenated chain, or bare base64 with the armour stripped. Everything is
 * reduced to the first CA certificate found, so a stored entry is always
 * exactly one trust anchor.
 */
object CertificatePem {

    enum class Result { Valid, NotCa, Unparsable }

    fun validate(input: String): Result {
        val certs = parse(input)
        return when {
            certs.isEmpty() -> Result.Unparsable
            certs.none(::isCa) -> Result.NotCa
            else -> Result.Valid
        }
    }

    fun normalize(input: String): String? = firstCa(input)?.let { encode(it.encoded) }

    fun label(input: String): String? {
        val cert = firstCa(input) ?: return null
        val dn = cert.subjectX500Principal.getName(X500Principal.RFC2253)
        return attribute(dn, "CN") ?: attribute(dn, "O") ?: dn.takeIf { it.isNotEmpty() }
    }

    private fun firstCa(input: String): X509Certificate? = parse(input).firstOrNull(::isCa)

    /** [X509Certificate.getBasicConstraints] returns -1 for non-CA certificates. */
    private fun isCa(cert: X509Certificate): Boolean = cert.basicConstraints >= 0

    private fun parse(input: String): List<X509Certificate> {
        val armoured = armour(input) ?: return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        return runCatching {
            factory.generateCertificates(ByteArrayInputStream(armoured.toByteArray()))
                .filterIsInstance<X509Certificate>()
        }.getOrDefault(emptyList())
    }

    private fun armour(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(BEGIN)) return trimmed
        val body = trimmed.filterNot { it.isWhitespace() }
        val der = runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull() ?: return null
        return encode(der)
    }

    // No trailing newline: stored entries are trimmed by WebAppSettings.sanitize,
    // so emitting one would make normalized values differ from persisted ones.
    private fun encode(der: ByteArray): String {
        val body = Base64.encodeToString(der, Base64.NO_WRAP)
        return buildString {
            append(BEGIN).append('\n')
            body.chunked(LINE_LENGTH).forEach { append(it).append('\n') }
            append(END)
        }
    }

    private fun attribute(dn: String, key: String): String? =
        Regex("(?:^|,)$key=([^,]+)").find(dn)?.groupValues?.get(1)?.trim()?.takeIf {
            it.isNotEmpty()
        }

    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"
    private const val LINE_LENGTH = 64
}
