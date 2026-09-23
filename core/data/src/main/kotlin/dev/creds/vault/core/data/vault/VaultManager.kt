package dev.creds.vault.core.data.vault

import dev.creds.vault.core.crypto.BiometricKeyStore
import dev.creds.vault.core.crypto.KdfParams
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.crypto.VaultKeySealer
import dev.creds.vault.core.crypto.VaultSession
import dev.creds.vault.core.crypto.VaultState
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.data.prefs.LockPreferences
import dev.creds.vault.core.data.prefs.VaultKeyStore
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.domain.lock.UnlockBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.crypto.Cipher

/**
 * The vault's lifecycle: create, unlock, lock.
 *
 * This is the module's public face. `CredsDatabase`, its DAOs and `VaultDatabaseFactory`
 * are all `internal`, so nothing above this layer can hold a database that outlives a
 * lock — which is the point. Unlocking opens the database, locking closes it and zeroes
 * the key, and there is no third path.
 *
 * Failure counting and backoff live here rather than in a ViewModel because they must
 * survive the process being killed. An attacker who can restart the app to clear the
 * lockout has no lockout.
 *
 * Constructed by `DataModule` rather than by an `@Inject` constructor: the class is
 * public but several of its dependencies are `internal` to this module, and a public
 * constructor cannot name them.
 */
class VaultManager internal constructor(
    private val keyStore: VaultKeyStore,
    private val lockPreferences: LockPreferences,
    private val sealer: VaultKeySealer,
    private val session: VaultSession,
    private val databaseFactory: VaultDatabaseFactory,
    private val biometricKeyStore: BiometricKeyStore,
    private val attachmentStore: AttachmentStore,
) {

    val state: Flow<VaultState> = session.state

    val isUnlocked: Boolean get() = session.isUnlocked

    @Volatile
    private var database: CredsDatabase? = null

    private val _repository = MutableStateFlow<VaultRepository?>(null)

    /**
     * The open repository, or null while locked.
     *
     * Screens observe this rather than [state]. The session reports "unlocked" a moment
     * before the database has finished opening, so a screen keyed off [state] can ask for
     * a repository that does not exist yet; this only becomes non-null once it does, and
     * becomes null again *before* the database is closed.
     */
    val repository: StateFlow<VaultRepository?> = _repository.asStateFlow()

    /** Whether setup has run. Decides between the setup and unlock screens at launch. */
    suspend fun isInitialised(): Boolean = keyStore.isInitialised()

    /**
     * Creates a new vault and leaves it unlocked.
     *
     * The vault key is generated here and sealed under the password; it is never derived
     * from the password. That is what makes a later password change an O(1) reseal
     * instead of re-encrypting everything.
     *
     * [password] belongs to the caller, who must wipe it — setup may still need it to
     * seal a recovery copy.
     */
    suspend fun createVault(
        password: CharArray,
        params: KdfParams = KdfParams.DEFAULT,
    ) {
        check(!keyStore.isInitialised()) { "A vault already exists" }

        val vaultKey = VaultKey.generate()
        keyStore.storeSealedVaultKey(sealer.seal(vaultKey, password, params))
        keyStore.clearFailures()

        openWith(vaultKey)
    }

    /**
     * Attempts an unlock with the master password.
     *
     * Argon2id is deliberately slow, so this must be called off the main thread.
     */
    suspend fun unlock(password: CharArray, now: Long): UnlockResult {
        val sealed = keyStore.sealedVaultKey() ?: return UnlockResult.NotInitialised

        val failures = keyStore.failedAttempts()
        val waitMillis = UnlockBackoff.remainingMillis(failures, keyStore.lastFailureAt(), now)
        if (waitMillis > 0) return UnlockResult.LockedOut(waitMillis)

        val vaultKey = sealer.open(sealed, password)
            ?: return recordFailure(now)

        keyStore.clearFailures()
        openWith(vaultKey)
        return UnlockResult.Success
    }

    /**
     * Completes a biometric unlock with a cipher `BiometricPrompt` has authorised.
     *
     * Biometric failures do not feed the backoff counter: the Keystore key already
     * enforces its own attempt limits in hardware, and counting them here would let
     * someone lock the owner out of password unlock by waving a wrong finger.
     */
    suspend fun unlockWithBiometric(cipher: Cipher): UnlockResult {
        val sealed = keyStore.biometricSealedKey() ?: return UnlockResult.BiometricUnavailable

        val vaultKey = try {
            biometricKeyStore.openWith(cipher, sealed)
        } catch (_: Exception) {
            // The stored blob no longer matches the Keystore key. Dropping it is the
            // honest response: biometric unlock is gone until re-enrolled, and the
            // master password still works.
            keyStore.clearBiometricSealedKey()
            lockPreferences.setBiometricUnlock(false)
            return UnlockResult.BiometricUnavailable
        }

        keyStore.clearFailures()
        openWith(vaultKey)
        return UnlockResult.Success
    }

    /**
     * Seals a second copy of the vault key under the Keystore biometric key.
     *
     * Requires the vault to already be unlocked — there is no way to enrol a door you
     * cannot currently open.
     */
    suspend fun enableBiometric(cipher: Cipher) {
        session.withVaultKey { vaultKey ->
            biometricKeyStore.sealWith(cipher, vaultKey)
        }.also { keyStore.storeBiometricSealedKey(it) }

        lockPreferences.setBiometricUnlock(true)
    }

    suspend fun disableBiometric() {
        keyStore.clearBiometricSealedKey()
        biometricKeyStore.deleteKey()
        lockPreferences.setBiometricUnlock(false)
    }

    /**
     * Locks the vault: closes the database and zeroes the key.
     *
     * Idempotent, because every lock trigger — backgrounding, screen off, idle timeout,
     * the user's own button — can fire more than once and in any order.
     */
    fun lock() {
        // Order matters. Observers learn the vault is locked first, then the key is
        // zeroed, and only then does the database close under any query still in flight.
        // A query failing against a closed database is then distinguishable from a real
        // error: by the time it fails, the repository it came from is no longer current.
        // Closing after the wipe is safe because the SQLCipher passphrase is a separate
        // array derived at open time.
        _repository.value = null
        session.lock()
        database?.close()
        database = null
    }

    /**
     * The repository, which only exists while unlocked.
     *
     * Throws rather than returning null: anything calling this while locked is a
     * navigation bug, and failing quietly would mean rendering an empty vault instead of
     * a locked one.
     */
    fun requireRepository(): VaultRepository =
        _repository.value ?: error("Vault is locked")

    /**
     * Runs [block] against the open vault, or returns null when it is locked.
     *
     * For user actions — a tap that arrives just after an idle lock is ordinary, not a
     * bug. The key handed to [block] is live only while the vault stays unlocked; if a
     * lock lands mid-operation, deriving from it throws rather than using zeroed bytes.
     */
    suspend fun <R> withUnlocked(block: suspend (VaultRepository, VaultKey) -> R): R? {
        val repository = _repository.value ?: return null
        val unlocked = session.state.value as? VaultState.Unlocked ?: return null
        return block(repository, unlocked.vaultKey)
    }

    private suspend fun openWith(vaultKey: VaultKey) {
        // Take ownership before opening: if the database throws, the session still holds
        // the key and lock() will wipe it, rather than it being stranded unreferenced.
        session.unlock(vaultKey)

        // Closes a database left over from a previous unlock rather than leaking it.
        database?.close()

        val opened = databaseFactory.open(vaultKey)
        database = opened
        val repository = VaultRepository(opened, FieldCipher(), attachmentStore)

        // Before the repository is published, so no save can be mid-flight: a file it had
        // written but not yet committed would otherwise look exactly like an orphan.
        try {
            repository.sweepAttachments()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Leftover ciphertext costs disk space, not secrecy. Never block an unlock on it.
        }

        _repository.value = repository
    }

    private suspend fun recordFailure(now: Long): UnlockResult {
        val failures = keyStore.recordFailure(now)
        val policy = lockPreferences.policy.first()

        if (policy.wipeEnabled && failures >= policy.wipeAfterFailures) {
            wipe()
            return UnlockResult.Wiped
        }

        return UnlockResult.WrongPassword(
            failures = failures,
            retryAfterMillis = UnlockBackoff.delayMillis(failures),
        )
    }

    /**
     * Destroys the vault. There is no recovery from this.
     *
     * Deleting the sealed key alone would be enough to make the database unreadable
     * forever, but the file is deleted too so the ciphertext cannot be kept for a future
     * attack on the password.
     */
    private suspend fun wipe() {
        lock()
        databaseFactory.delete()
        attachmentStore.deleteAll()
        keyStore.clearAll()
        biometricKeyStore.deleteKey()
    }
}

/** The outcome of an unlock attempt. */
sealed interface UnlockResult {

    data object Success : UnlockResult

    /** No vault exists yet; the caller should route to setup. */
    data object NotInitialised : UnlockResult

    /**
     * Wrong password.
     *
     * @param retryAfterMillis how long the next attempt is blocked for, so the UI can
     *   show a countdown instead of an unexplained dead button.
     */
    data class WrongPassword(val failures: Int, val retryAfterMillis: Long) : UnlockResult

    /** An attempt was made during a backoff window. */
    data class LockedOut(val retryAfterMillis: Long) : UnlockResult

    /** The failure threshold was reached and the vault was destroyed. */
    data object Wiped : UnlockResult

    /** Biometric unlock is not set up, or the Keystore key was invalidated. */
    data object BiometricUnavailable : UnlockResult
}
