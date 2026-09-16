package dev.creds.vault.core.crypto

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException

class AesGcmTest {

    private val key = ByteArray(32) { it.toByte() }
    private val plaintext = "correct horse battery staple".toByteArray()

    @Test
    fun `round trips`() {
        val sealed = AesGcm.seal(key, plaintext)
        assertThat(AesGcm.open(key, sealed).decodeToString())
            .isEqualTo("correct horse battery staple")
    }

    @Test
    fun `round trips with associated data`() {
        val aad = "field-uid-42".toByteArray()

        val sealed = AesGcm.seal(key, plaintext, aad)

        assertThat(AesGcm.open(key, sealed, aad).decodeToString())
            .isEqualTo("correct horse battery staple")
    }

    @Test
    fun `round trips an empty plaintext`() {
        val sealed = AesGcm.seal(key, ByteArray(0))
        assertThat(AesGcm.open(key, sealed)).hasSize(0)
    }

    @Test
    fun `framing is version, nonce, then body`() {
        val sealed = AesGcm.seal(key, plaintext)

        assertThat(sealed[0]).isEqualTo(1.toByte())
        // 1 version + 12 nonce + ciphertext (same length as plaintext for GCM) + 16 tag.
        assertThat(sealed).hasSize(1 + 12 + plaintext.size + 16)
    }

    @Test
    fun `nonce differs per seal`() {
        val first = AesGcm.seal(key, plaintext)
        val second = AesGcm.seal(key, plaintext)

        // Same key, same plaintext, different ciphertext. A repeated nonce under one key
        // is catastrophic for GCM, so this is worth asserting rather than assuming.
        assertThat(first.hex()).isNotEqualTo(second.hex())
        assertThat(first.copyOfRange(1, 13).hex()).isNotEqualTo(second.copyOfRange(1, 13).hex())
    }

    @Test
    fun `wrong key fails authentication`() {
        val sealed = AesGcm.seal(key, plaintext)
        val wrongKey = ByteArray(32) { (it + 1).toByte() }

        assertThrows<GeneralSecurityException> { AesGcm.open(wrongKey, sealed) }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val sealed = AesGcm.seal(key, plaintext)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()

        assertThrows<GeneralSecurityException> { AesGcm.open(key, sealed) }
    }

    @Test
    fun `tampered nonce fails authentication`() {
        val sealed = AesGcm.seal(key, plaintext)
        sealed[1] = (sealed[1] + 1).toByte()

        assertThrows<GeneralSecurityException> { AesGcm.open(key, sealed) }
    }

    @Test
    fun `mismatched associated data fails authentication`() {
        val sealed = AesGcm.seal(key, plaintext, "field-uid-42".toByteArray())

        // This is what stops a valid ciphertext being moved to another row.
        assertThrows<GeneralSecurityException> {
            AesGcm.open(key, sealed, "field-uid-43".toByteArray())
        }
        assertThrows<GeneralSecurityException> { AesGcm.open(key, sealed) }
    }

    @Test
    fun `unknown version is rejected`() {
        val sealed = AesGcm.seal(key, plaintext)
        sealed[0] = 99

        assertThrows<GeneralSecurityException> { AesGcm.open(key, sealed) }
    }

    @Test
    fun `truncated blob is rejected`() {
        val sealed = AesGcm.seal(key, plaintext)

        assertThrows<GeneralSecurityException> { AesGcm.open(key, sealed.copyOf(10)) }
        assertThrows<GeneralSecurityException> { AesGcm.open(key, ByteArray(0)) }
    }

    @Test
    fun `rejects keys that are not 256 bit`() {
        assertThrows<IllegalArgumentException> { AesGcm.seal(ByteArray(16), plaintext) }
        assertThrows<IllegalArgumentException> { AesGcm.open(ByteArray(16), ByteArray(64)) }
    }

    @Test
    fun `random key and bytes have the requested size and vary`() {
        assertThat(AesGcm.randomKey()).hasSize(32)
        assertThat(AesGcm.randomBytes(16)).hasSize(16)
        assertThat(AesGcm.randomKey().hex() != AesGcm.randomKey().hex()).isTrue()
    }
}
