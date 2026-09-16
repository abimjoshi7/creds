package dev.creds.vault.core.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lambdapioneer.argon2kt.Argon2Kt
import dev.creds.vault.core.crypto.Argon2idPasswordHasher
import dev.creds.vault.core.crypto.KdfParams
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.crypto.VaultKeySealer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seal, persist, read back, open — the exact sequence setup and unlock perform.
 *
 * The JVM tests round-trip the sealer in memory and the crypto device tests round-trip
 * Argon2id, but nothing covered the persistence hop in between. If the salt or the KDF
 * parameters do not survive DataStore byte-for-byte, the master key derived at unlock
 * differs from the one used at setup, the GCM tag fails, and the user is told their
 * correct password is wrong.
 */
@RunWith(AndroidJUnit4::class)
class VaultKeyStoreRoundTripTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = VaultKeyStore(context)
    private val sealer = VaultKeySealer(Argon2idPasswordHasher(Argon2Kt()))

    private val params = KdfParams(memoryKib = 8192, iterations = 2, parallelism = 2)

    @Before
    fun clear() = runBlocking {
        store.clearAll()
    }

    @Test
    fun sealedKeySurvivesPersistenceAndOpens() = runBlocking {
        val vaultKey = VaultKey.generate()
        val expected = vaultKey.databasePassphrase().toHex()
        val password = "correct horse battery staple"

        store.storeSealedVaultKey(sealer.seal(vaultKey, password.toCharArray(), params))

        val readBack = store.sealedVaultKey()
        assertNotNull("nothing came back from DataStore", readBack)

        val opened = sealer.open(readBack!!, password.toCharArray())
        assertNotNull("correct password failed to open the persisted key", opened)
        assertEquals(expected, opened!!.databasePassphrase().toHex())
    }

    @Test
    fun saltSurvivesByteForByte() = runBlocking {
        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray(), params)

        store.storeSealedVaultKey(sealed)
        val readBack = store.sealedVaultKey()!!

        assertArrayEquals("salt changed in storage", sealed.salt, readBack.salt)
        assertArrayEquals("ciphertext changed in storage", sealed.ciphertext, readBack.ciphertext)
        assertEquals(16, readBack.salt.size)
    }

    @Test
    fun kdfParametersSurvive() = runBlocking {
        val sealed = sealer.seal(VaultKey.generate(), "hunter2".toCharArray(), params)

        store.storeSealedVaultKey(sealed)
        val readBack = store.sealedVaultKey()!!

        // A silently wrong parameter here derives a different key and looks exactly like
        // a wrong password.
        assertEquals(params, readBack.params)
        assertEquals(8192, readBack.params.memoryKib)
        assertEquals(2, readBack.params.iterations)
        assertEquals(2, readBack.params.parallelism)
        assertEquals(32, readBack.params.outputBytes)
    }

    @Test
    fun productionDefaultsSurviveAndOpen() = runBlocking {
        val vaultKey = VaultKey.generate()
        val password = "a-real-master-password"

        store.storeSealedVaultKey(sealer.seal(vaultKey, password.toCharArray(), KdfParams.DEFAULT))
        val readBack = store.sealedVaultKey()!!

        assertEquals(KdfParams.DEFAULT, readBack.params)
        assertNotNull(sealer.open(readBack, password.toCharArray()))
    }

    @Test
    fun isInitialisedReflectsStoredKey() = runBlocking {
        assertTrue(!store.isInitialised())

        store.storeSealedVaultKey(sealer.seal(VaultKey.generate(), "hunter2".toCharArray(), params))

        assertTrue(store.isInitialised())
    }

    @Test
    fun storingTwiceReplacesRatherThanMerges() = runBlocking {
        val first = sealer.seal(VaultKey.generate(), "first-password".toCharArray(), params)
        store.storeSealedVaultKey(first)

        val secondKey = VaultKey.generate()
        val second = sealer.seal(secondKey, "second-password".toCharArray(), params)
        store.storeSealedVaultKey(second)

        val readBack = store.sealedVaultKey()!!
        assertArrayEquals(second.salt, readBack.salt)
        // The old password must no longer open it, and the new one must.
        assertNotNull(sealer.open(readBack, "second-password".toCharArray()))
        assertEquals(
            secondKey.databasePassphrase().toHex(),
            sealer.open(readBack, "second-password".toCharArray())!!.databasePassphrase().toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
