package dev.creds.vault.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lambdapioneer.argon2kt.Argon2Kt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seal and open the vault key with the *real* Argon2id hasher.
 *
 * This is the gap that let a broken unlock ship: `VaultKeySealerTest` round-trips on the
 * JVM but substitutes a SHA-256 stand-in for the KDF, and `Argon2idKnownAnswerTest`
 * checks Argon2 vectors without ever sealing anything. Neither one exercises
 * derive → seal → derive again → open, which is exactly what setup and unlock do.
 *
 * Uses reduced cost parameters so the suite stays quick; the parameters under test are
 * the round-trip, not the work factor, and `VaultKeySealerTest` pins the production
 * defaults separately.
 */
@RunWith(AndroidJUnit4::class)
class VaultKeySealerRoundTripTest {

    private val sealer = VaultKeySealer(Argon2idPasswordHasher(Argon2Kt()))

    private val params = KdfParams(memoryKib = 8192, iterations = 2, parallelism = 2)

    @Test
    fun sealsAndOpensWithTheSamePassword() {
        val vaultKey = VaultKey.generate()
        val expected = vaultKey.databasePassphrase().toHex()

        val sealed = sealer.seal(vaultKey, "correct horse battery staple".toCharArray(), params)
        val opened = sealer.open(sealed, "correct horse battery staple".toCharArray())

        assertNotNull("real Argon2id round-trip failed to open", opened)
        assertEquals(expected, opened!!.databasePassphrase().toHex())
    }

    @Test
    fun opensAfterSealingWithProductionDefaults() {
        // The real cost parameters, once, because that is what a user's vault actually
        // uses and a mismatch there would only show up at unlock.
        val vaultKey = VaultKey.generate()
        val expected = vaultKey.databasePassphrase().toHex()

        val sealed = sealer.seal(vaultKey, "hunter2hunter2".toCharArray(), KdfParams.DEFAULT)
        val opened = sealer.open(sealed, "hunter2hunter2".toCharArray())

        assertNotNull(opened)
        assertEquals(expected, opened!!.databasePassphrase().toHex())
    }

    @Test
    fun wrongPasswordReturnsNull() {
        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray(), params)

        assertNull(sealer.open(sealed, "hunter3".toCharArray()))
    }

    @Test
    fun opensAfterTheSealingBuffersAreReused() {
        // Setup seals with a buffer it then wipes. If anything downstream held a
        // reference to that buffer rather than copying it, the second derivation would
        // differ and unlock would fail with a correct password.
        val vaultKey = VaultKey.generate()
        val password = "correct horse battery staple".toCharArray()

        val sealed = sealer.seal(vaultKey, password, params)
        // The caller owns and keeps the buffer, exactly as SetupViewModel does.
        assertEquals("correct horse battery staple", String(password))

        val opened = sealer.open(sealed, password)
        assertNotNull(opened)
    }

    @Test
    fun unicodePasswordsRoundTrip() {
        val vaultKey = VaultKey.generate()
        val password = "pässwörtchen-日本語-🔐"

        val sealed = sealer.seal(vaultKey, password.toCharArray(), params)

        assertNotNull(sealer.open(sealed, password.toCharArray()))
    }

    @Test
    fun resealedKeyOpensWithTheNewPassword() {
        val vaultKey = VaultKey.generate()
        val expected = vaultKey.databasePassphrase().toHex()

        val resealed = sealer.reseal(vaultKey, "new-password-here".toCharArray(), params)
        val opened = sealer.open(resealed, "new-password-here".toCharArray())

        assertNotNull(opened)
        assertEquals(expected, opened!!.databasePassphrase().toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
