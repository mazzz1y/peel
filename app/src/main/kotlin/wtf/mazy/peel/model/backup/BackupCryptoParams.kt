package wtf.mazy.peel.model.backup

import android.util.Base64
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

// Defaults are encoded so archives stay readable after the kdf or iteration count changes.
@Serializable
data class BackupCryptoParams(
    @EncodeDefault val version: Int = BackupPolicy.CRYPTO_PARAMS_VERSION,
    @EncodeDefault val kdf: String = BackupCrypto.KDF_PBKDF2_HMAC_SHA256,
    @EncodeDefault val iterations: Int = BackupCrypto.ITERATIONS,
    val salt: String,
    val iv: String,
) {
    val saltBytes: ByteArray by lazy { decode(salt) }

    val ivBytes: ByteArray by lazy { decode(iv) }

    // crypto.json is attacker-controlled and the tag only verifies after derivation,
    // so an unbounded iteration count would burn arbitrary CPU before it could fail.
    fun isSupported(): Boolean =
        version == BackupPolicy.CRYPTO_PARAMS_VERSION &&
                kdf == BackupCrypto.KDF_PBKDF2_HMAC_SHA256 &&
                iterations in MIN_ITERATIONS..MAX_ITERATIONS &&
                decodeOrNull(salt)?.size == BackupCrypto.SALT_BYTES &&
                decodeOrNull(iv)?.size == BackupCrypto.IV_BYTES

    companion object {
        private const val MIN_ITERATIONS = 100_000
        private const val MAX_ITERATIONS = 2_000_000

        fun of(salt: ByteArray, iv: ByteArray): BackupCryptoParams =
            BackupCryptoParams(salt = encode(salt), iv = encode(iv))

        private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

        private fun decodeOrNull(value: String): ByteArray? =
            try {
                decode(value)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
