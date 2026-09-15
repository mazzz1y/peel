package wtf.mazy.peel.model.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {

    const val KDF_PBKDF2_HMAC_SHA256 = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 1_000_000

    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val TAG_BYTES = 16

    private const val KEY_ALGORITHM = "AES"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val TAG_BITS = TAG_BYTES * 8

    fun newSalt(): ByteArray = randomBytes(SALT_BYTES)

    fun newIv(): ByteArray = randomBytes(IV_BYTES)

    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        params: BackupCryptoParams,
        associatedData: ByteArray,
    ): ByteArray = runCipher(Cipher.ENCRYPT_MODE, plaintext, password, params, associatedData)

    /** Throws [javax.crypto.AEADBadTagException] when the password is wrong or the input was altered. */
    fun decrypt(
        ciphertext: ByteArray,
        password: CharArray,
        params: BackupCryptoParams,
        associatedData: ByteArray,
    ): ByteArray = runCipher(Cipher.DECRYPT_MODE, ciphertext, password, params, associatedData)

    private fun runCipher(
        mode: Int,
        input: ByteArray,
        password: CharArray,
        params: BackupCryptoParams,
        associatedData: ByteArray,
    ): ByteArray {
        val key = deriveKey(password, params)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        // Android assumes a zero IV when none is supplied, so the spec is always explicit.
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, params.ivBytes))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(input)
    }

    private fun deriveKey(password: CharArray, params: BackupCryptoParams): SecretKeySpec {
        // Passed as char[] with no pre-encoding: Android registers this factory against
        // the UTF-8 scheme, and pre-encoding would diverge from standard PBKDF2.
        val spec = PBEKeySpec(password, params.saltBytes, params.iterations, KEY_BITS)
        val derived = try {
            SecretKeyFactory.getInstance(params.kdf).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(derived, KEY_ALGORITHM).also { derived.fill(0) }
    }

    private val secureRandom = SecureRandom()

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { secureRandom.nextBytes(it) }
}
