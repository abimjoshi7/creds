package dev.creds.vault.core.data.vault

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lambdapioneer.argon2kt.Argon2Kt
import dev.creds.vault.core.crypto.Argon2idPasswordHasher
import dev.creds.vault.core.crypto.BiometricKeyStore
import dev.creds.vault.core.crypto.KdfParams
import dev.creds.vault.core.crypto.VaultKeySealer
import dev.creds.vault.core.crypto.VaultSession
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.data.prefs.LockPreferences
import dev.creds.vault.core.data.prefs.VaultKeyStore
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Changing the master password through the real lifecycle: Argon2id, the persisted sealed
 * key, and a relock and unlock afterwards. Runs in the test package's own storage.
 */
@RunWith(AndroidJUnit4::class)
class ChangeMasterPasswordTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val keyStore = VaultKeyStore(context)
    private val manager = VaultManager(
        keyStore = keyStore,
        lockPreferences = LockPreferences(context),
        sealer = VaultKeySealer(Argon2idPasswordHasher(Argon2Kt())),
        session = VaultSession(),
        databaseFactory = VaultDatabaseFactory(context),
        biometricKeyStore = BiometricKeyStore(),
        attachmentStore = AttachmentStore(File(context.cacheDir, "change-password-attachments")),
    )

    @Before
    @After
    fun clean() {
        runBlocking {
            manager.lock()
            keyStore.clearAll()
        }
        context.deleteDatabase(CredsDatabase.NAME)
    }

    @Test
    fun theNewPasswordOpensTheSameVaultAndTheOldOneNoLongerDoes() = runBlocking {
        manager.createVault(OLD.toCharArray(), CHEAP)
        manager.withUnlocked { repository, key ->
            repository.save(key, VaultItem(uuid = "kept", template = Template.NOTE, title = "Kept", createdAt = 1, updatedAt = 1))
        }

        assertEquals(ChangePasswordResult.Changed, manager.changeMasterPassword(OLD.toCharArray(), NEW.toCharArray()))
        manager.lock()

        assertTrue(manager.unlock(OLD.toCharArray(), now = 1) is UnlockResult.WrongPassword)
        assertEquals(UnlockResult.Success, manager.unlock(NEW.toCharArray(), now = 100_000))
        // Same vault key, so the data is simply there — nothing was re-encrypted.
        assertEquals("Kept", manager.withUnlocked { repository, key -> repository.load(key, "kept")?.title })
    }

    @Test
    fun aWrongCurrentPasswordChangesNothingAndCountsForNothing() = runBlocking {
        manager.createVault(OLD.toCharArray(), CHEAP)

        assertEquals(ChangePasswordResult.WrongPassword, manager.changeMasterPassword("not-it-at-all".toCharArray(), NEW.toCharArray()))
        assertEquals("a settings typo must not move toward a wipe", 0, keyStore.failedAttempts())

        manager.lock()
        assertEquals(UnlockResult.Success, manager.unlock(OLD.toCharArray(), now = 1))
    }

    @Test
    fun nothingChangesWhileLocked() = runBlocking {
        manager.createVault(OLD.toCharArray(), CHEAP)
        manager.lock()

        assertEquals(ChangePasswordResult.Locked, manager.changeMasterPassword(OLD.toCharArray(), NEW.toCharArray()))
        assertEquals(UnlockResult.Success, manager.unlock(OLD.toCharArray(), now = 1))
    }

    private companion object {
        const val OLD = "first-Master-pass1"
        const val NEW = "second-Master-pass2"
        val CHEAP = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1)
    }
}
