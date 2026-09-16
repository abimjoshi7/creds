package dev.creds.vault.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM sealing for keys and field values.
 *
 * Every sealed blob is self-contained and framed as:
 * ```
 * [ 1 byte version ][ 12 byte nonce ][ ciphertext || 16 byte tag ]
 * ```
 * The version byte costs nothing now and is the difference between a clean algorithm
 * migration later and a database we cannot read.
 *
 * Nonces are random per seal. At a 96-bit nonce the birthday bound only becomes a real
 * concern past ~2^32 seals under one key, and no key here is used remotely that often:
 * field keys are per-item, and the vault key is sealed a handful of times in its life.
 *
 * This class never touches the Android Keystore. Keystore-backed AES-GCM generates its
 * own IV inside the secure element and is driven through a [Cipher] the caller already
 * holds — see [BiometricKeyStore].
 */
object AesGcm {

    const val KEY_BYTES: Int = 32
    const val NONCE_BYTES: Int = 12
    const val TAG_BITS: Int = 128
    private const val TAG_BYTES = TAG_BITS / 8

    private const val VERSION_V1: Byte = 1
    private const val HEADER_BYTES = 1 + NONCE_BYTES

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    /**
     * Seals [plaintext] under [key].
     *
     * @param aad optional associated data — authenticated but not encrypted. Bind a blob
     *   to its context with it (a field's uid, say) so a valid ciphertext cannot be moved
     *   to another row and still decrypt.
     */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 needs a $KEY_BYTES byte key, got ${key.size}" }

        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val secretKey = SecretKeySpec(key, "AES")

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
            aad?.let(cipher::updateAAD)

            val body = cipher.doFinal(plaintext)

            val sealed = ByteArray(HEADER_BYTES + body.size)
            sealed[0] = VERSION_V1
            nonce.copyInto(sealed, 1)
            body.copyInto(sealed, HEADER_BYTES)
            return sealed
        } finally {
            // SecretKeySpec keeps its own copy of the key bytes; drop our reference to it.
            zeroSecretKeySpec(secretKey)
        }
    }

    /**
     * Opens a blob produced by [seal].
     *
     * @throws GeneralSecurityException if the key is wrong, the blob was tampered with,
     *   or the framing is not one we understand. The caller must not distinguish these
     *   cases to the user beyond "could not decrypt".
     */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 needs a $KEY_BYTES byte key, got ${key.size}" }

        if (sealed.size < HEADER_BYTES + TAG_BYTES) {
            throw GeneralSecurityException("Sealed blob is too short to be valid")
        }
        if (sealed[0] != VERSION_V1) {
            throw GeneralSecurityException("Unsupported sealed blob version ${sealed[0]}")
        }

        val nonce = sealed.copyOfRange(1, HEADER_BYTES)
        val secretKey = SecretKeySpec(key, "AES")

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
            aad?.let(cipher::updateAAD)

            return cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } finally {
            zeroSecretKeySpec(secretKey)
        }
    }

    /** Fresh 256-bit key from [SecureRandom]. The caller owns wiping it. */
    fun randomKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** Fresh random bytes, for salts and vault keys. */
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    /**
     * Best-effort erasure of a [SecretKeySpec]'s internal copy.
     *
     * `destroy()` is a no-op on most JCE providers and throws
     * `DestroyFailedException` on others, so this is genuinely best-effort. It is still
     * worth attempting; where the provider honours it, one more copy of the key stops
     * existing.
     */
    private fun zeroSecretKeySpec(key: SecretKeySpec) {
        try {
            key.destroy()
        } catch (_: Exception) {
            // Provider does not support destruction. Nothing further we can do.
        }
    }
}
