package dev.creds.vault.core.crypto

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Sealing logic, exercised with a stand-in for Argon2id.
 *
 * The real hasher needs argon2kt's native library and is covered by
 * `Argon2idKnownAnswerTest` on device. What matters here is the *sealing* contract —
 * round-trip, wrong-password handling, fresh salts, corruption surfacing — and none of
 * that depends on which KDF is underneath.
 */
class VaultKeySealerTest {

    /**
     * Deterministic stand-in: SHA-256 over password and salt.
     *
     * Deliberately not a constant — it must still be password- and salt-dependent, or
     * the wrong-password and fresh-salt tests would pass vacuously.
     */
    private class FakePasswordHasher : PasswordHasher {
        var calls = 0
            private set

        override fun deriveMasterKey(
            password: CharArray,
            salt: ByteArray,
            params: KdfParams,
        ): ByteArray {
            calls++
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update(password.toUtf8Bytes())
            return digest.digest()
        }
    }

    private val hasher = FakePasswordHasher()
    private val sealer = VaultKeySealer(hasher)

    @Test
    fun `seals and opens with the same password`() {
        val vaultKey = VaultKey.generate()
        val expected = vaultKey.databasePassphrase().hex()

        val sealed = sealer.seal(vaultKey, "hunter2".toCharArray())
        val opened = sealer.open(sealed, "hunter2".toCharArray())

        assertThat(opened).isNotNull()
        assertThat(opened!!.databasePassphrase().hex()).isEqualTo(expected)
    }

    @Test
    fun `wrong password returns null`() {
        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray())

        // Null, not an exception: a wrong password is the expected path and the unlock
        // screen's backoff handles it.
        assertThat(sealer.open(sealed, "hunter3".toCharArray())).isNull()
    }

    @Test
    fun `corrupt blob throws rather than reporting a wrong password`() {
        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray())
        val truncated = sealed.copy(ciphertext = sealed.ciphertext.copyOf(8))

        // Corruption must not masquerade as a typo, or the user retypes a correct
        // password forever against a damaged vault.
        assertThrows<GeneralSecurityException> {
            sealer.open(truncated, "hunter2".toCharArray())
        }
    }

    @Test
    fun `each seal uses a fresh salt`() {
        val vaultKey = VaultKey.generate()

        val first = sealer.seal(vaultKey, "hunter2".toCharArray())
        val second = sealer.seal(vaultKey, "hunter2".toCharArray())

        assertThat(first.salt.hex()).isNotEqualTo(second.salt.hex())
        assertThat(first.ciphertext.hex()).isNotEqualTo(second.ciphertext.hex())
    }

    @Test
    fun `salt is 128 bit`() {
        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray())

        assertThat(sealed.salt).hasSize(16)
    }

    @Test
    fun `seal does not consume the caller's password buffer`() {
        val password = "hunter2".toCharArray()

        sealer.seal(VaultKey.generate(), password)

        // Setup seals the same vault key under several doors and needs the password
        // again afterwards.
        assertThat(String(password)).isEqualTo("hunter2")
    }

    @Test
    fun `reseal keeps the same vault key under a new password`() {
        val vaultKey = VaultKey.generate()
        val expected = vaultKey.databasePassphrase().hex()
        val original = sealer.seal(vaultKey, "old-password".toCharArray())

        val resealed = sealer.reseal(vaultKey, "new-password".toCharArray())

        // The database is never rekeyed on a password change, so the derived passphrase
        // must be identical through the new door.
        val opened = sealer.open(resealed, "new-password".toCharArray())
        assertThat(opened).isNotNull()
        assertThat(opened!!.databasePassphrase().hex()).isEqualTo(expected)

        // And the old door must no longer open the new blob.
        assertThat(sealer.open(resealed, "old-password".toCharArray())).isNull()
        assertThat(original.salt.hex()).isNotEqualTo(resealed.salt.hex())
    }

    @Test
    fun `stored params round trip`() {
        val params = KdfParams(memoryKib = 16 * 1024, iterations = 4, parallelism = 1)

        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray(), params)

        // A vault created on old defaults must still open after we raise them.
        assertThat(sealed.params).isEqualTo(params)
        assertThat(sealer.open(sealed, "hunter2".toCharArray())).isNotNull()
    }

    @Test
    fun `equality compares content not identity`() {
        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray())
        val copy = sealed.copy(ciphertext = sealed.ciphertext.copyOf(), salt = sealed.salt.copyOf())

        assertThat(copy).isEqualTo(sealed)
        assertThat(copy.hashCode()).isEqualTo(sealed.hashCode())
    }

    @Test
    fun `KdfParams rejects impossible costs`() {
        assertThrows<IllegalArgumentException> { KdfParams(memoryKib = 8, iterations = 3, parallelism = 4) }
        assertThrows<IllegalArgumentException> { KdfParams(memoryKib = 1024, iterations = 0, parallelism = 1) }
        assertThrows<IllegalArgumentException> { KdfParams(memoryKib = 1024, iterations = 3, parallelism = 0) }
        assertThrows<IllegalArgumentException> {
            KdfParams(memoryKib = 1024, iterations = 3, parallelism = 1, outputBytes = 8)
        }
    }

    @Test
    fun `spec defaults are m64MiB t3 p2`() {
        // These are the numbers an offline attacker's cost model depends on. A silent
        // weakening here would never show up as a failure anywhere else.
        assertThat(KdfParams.DEFAULT.memoryKib).isEqualTo(65536)
        assertThat(KdfParams.DEFAULT.iterations).isEqualTo(3)
        assertThat(KdfParams.DEFAULT.parallelism).isEqualTo(2)
        assertThat(KdfParams.DEFAULT.outputBytes).isEqualTo(32)
        assertThat(KdfParams.SALT_BYTES).isEqualTo(16)
    }
}
