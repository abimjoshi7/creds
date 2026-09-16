package dev.creds.vault.core.crypto

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * RFC 5869 known-answer vectors.
 *
 * These are the RFC's own Appendix A cases for SHA-256, which is the entire point: the
 * key hierarchy is only as trustworthy as the KDF underneath it, and "it round-trips
 * with itself" would pass just as happily with a subtly wrong construction.
 */
class HkdfTest {

    @Test
    fun `A_1 basic case with salt and info`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Hkdf.extract(salt, ikm)
        assertThat(prk.hex()).isEqualTo(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
        )

        val okm = Hkdf.expand(prk, info, 42)
        assertThat(okm.hex()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
    }

    @Test
    fun `A_2 longer inputs spanning multiple expand blocks`() {
        val ikm = ByteArray(0x50) { it.toByte() }
        val salt = ByteArray(0x50) { (0x60 + it).toByte() }
        val info = ByteArray(0x50) { (0xb0 + it).toByte() }

        val prk = Hkdf.extract(salt, ikm)
        assertThat(prk.hex()).isEqualTo(
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
        )

        val okm = Hkdf.expand(prk, info, 82)
        assertThat(okm.hex()).isEqualTo(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
        )
    }

    @Test
    fun `A_3 zero-length salt and info`() {
        val ikm = hex("0b".repeat(22))

        val prk = Hkdf.extract(salt = null, ikm = ikm)
        assertThat(prk.hex()).isEqualTo(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
        )

        val okm = Hkdf.expand(prk, ByteArray(0), 42)
        assertThat(okm.hex()).isEqualTo(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
        )
    }

    @Test
    fun `empty salt is treated as HashLen zero bytes`() {
        val ikm = hex("0b".repeat(22))

        // RFC 5869 says an absent salt is HashLen zeros. An empty array must take the
        // same path, or callers passing ByteArray(0) silently get a different key.
        assertThat(Hkdf.extract(ByteArray(0), ikm).hex())
            .isEqualTo(Hkdf.extract(null, ikm).hex())
        assertThat(Hkdf.extract(ByteArray(32), ikm).hex())
            .isEqualTo(Hkdf.extract(null, ikm).hex())
    }

    @Test
    fun `derive matches extract then expand`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val stepwise = Hkdf.expand(Hkdf.extract(salt, ikm), info, 42)
        val combined = Hkdf.derive(ikm, salt, info, 42)

        assertThat(combined.hex()).isEqualTo(stepwise.hex())
    }

    @Test
    fun `different info gives unrelated output`() {
        val ikm = ByteArray(32) { 7 }

        val a = Hkdf.derive(ikm, null, "db".toByteArray(), 32)
        val b = Hkdf.derive(ikm, null, "reuse".toByteArray(), 32)

        assertThat(a.hex()).isNotEqualTo(b.hex())
    }

    @Test
    fun `expand rejects lengths outside the RFC bound`() {
        val prk = ByteArray(32)

        assertThrows<IllegalArgumentException> { Hkdf.expand(prk, ByteArray(0), 0) }
        assertThrows<IllegalArgumentException> { Hkdf.expand(prk, ByteArray(0), 255 * 32 + 1) }
    }

    @Test
    fun `expand emits exactly the requested length`() {
        val prk = ByteArray(32) { 3 }

        // Boundaries around the 32-byte block size, where an off-by-one would hide.
        for (length in listOf(1, 31, 32, 33, 64, 65, 255 * 32)) {
            assertThat(Hkdf.expand(prk, ByteArray(0), length)).hasSize(length)
        }
    }
}

internal fun hex(value: String): ByteArray =
    ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
