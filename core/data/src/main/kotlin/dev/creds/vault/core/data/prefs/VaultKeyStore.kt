package dev.creds.vault.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.crypto.BiometricSealedKey
import dev.creds.vault.core.crypto.KdfParams
import dev.creds.vault.core.crypto.SealedVaultKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore("vault")

/**
 * Persists the sealed vault key and the unlock state around it.
 *
 * Nothing stored here is secret on its own. The sealed key is AES-GCM ciphertext, the
 * salt and KDF parameters are public inputs, and all of it is useless without the master
 * password. That is the whole point of sealing the vault key rather than deriving it:
 * this file can be read by a backup agent or an attacker with the device unlocked and it
 * still yields nothing.
 *
 * The failure counter lives here rather than in memory precisely so that killing the app
 * does not reset the unlock backoff.
 */
@Singleton
class VaultKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** Whether setup has completed and a vault exists to unlock. */
    suspend fun isInitialised(): Boolean =
        read(Keys.SEALED_CIPHERTEXT) != null

    /**
     * The password-sealed vault key, or null before setup.
     *
     * Returns null rather than throwing when any part is missing: a half-written record
     * means setup was interrupted, which the caller handles by starting setup again.
     */
    suspend fun sealedVaultKey(): SealedVaultKey? {
        val prefs = context.vaultDataStore.data.first()
        val ciphertext = prefs[Keys.SEALED_CIPHERTEXT] ?: return null
        val salt = prefs[Keys.SEALED_SALT] ?: return null

        return SealedVaultKey(
            ciphertext = ciphertext,
            salt = salt,
            params = KdfParams(
                memoryKib = prefs[Keys.KDF_MEMORY_KIB] ?: KdfParams.DEFAULT.memoryKib,
                iterations = prefs[Keys.KDF_ITERATIONS] ?: KdfParams.DEFAULT.iterations,
                parallelism = prefs[Keys.KDF_PARALLELISM] ?: KdfParams.DEFAULT.parallelism,
                outputBytes = prefs[Keys.KDF_OUTPUT_BYTES] ?: KdfParams.DEFAULT.outputBytes,
            ),
        )
    }

    /**
     * Stores the password-sealed key, replacing any previous one.
     *
     * The KDF parameters are written alongside rather than assumed, so raising the
     * defaults later cannot lock an existing user out of their own vault.
     */
    suspend fun storeSealedVaultKey(sealed: SealedVaultKey) {
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.SEALED_CIPHERTEXT] = sealed.ciphertext
            prefs[Keys.SEALED_SALT] = sealed.salt
            prefs[Keys.KDF_MEMORY_KIB] = sealed.params.memoryKib
            prefs[Keys.KDF_ITERATIONS] = sealed.params.iterations
            prefs[Keys.KDF_PARALLELISM] = sealed.params.parallelism
            prefs[Keys.KDF_OUTPUT_BYTES] = sealed.params.outputBytes
        }
    }

    /** The biometric-sealed copy, or null when biometric unlock is off. */
    suspend fun biometricSealedKey(): BiometricSealedKey? {
        val prefs = context.vaultDataStore.data.first()
        val ciphertext = prefs[Keys.BIOMETRIC_CIPHERTEXT] ?: return null
        val iv = prefs[Keys.BIOMETRIC_IV] ?: return null
        return BiometricSealedKey(ciphertext = ciphertext, iv = iv)
    }

    suspend fun storeBiometricSealedKey(sealed: BiometricSealedKey) {
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.BIOMETRIC_CIPHERTEXT] = sealed.ciphertext
            prefs[Keys.BIOMETRIC_IV] = sealed.iv
        }
    }

    /**
     * Forgets the biometric copy.
     *
     * Called both when the user turns biometrics off and when the Keystore reports the
     * key was permanently invalidated by a new fingerprint enrolment — in the second
     * case the stored blob is already undecryptable, and keeping it would only produce a
     * confusing failure at every unlock.
     */
    suspend fun clearBiometricSealedKey() {
        context.vaultDataStore.edit { prefs ->
            prefs.remove(Keys.BIOMETRIC_CIPHERTEXT)
            prefs.remove(Keys.BIOMETRIC_IV)
        }
    }

    suspend fun failedAttempts(): Int = read(Keys.FAILED_ATTEMPTS) ?: 0

    suspend fun lastFailureAt(): Long = read(Keys.LAST_FAILURE_AT) ?: 0

    suspend fun recordFailure(now: Long): Int {
        var updated = 0
        context.vaultDataStore.edit { prefs ->
            updated = (prefs[Keys.FAILED_ATTEMPTS] ?: 0) + 1
            prefs[Keys.FAILED_ATTEMPTS] = updated
            prefs[Keys.LAST_FAILURE_AT] = now
        }
        return updated
    }

    suspend fun clearFailures() {
        context.vaultDataStore.edit { prefs ->
            prefs.remove(Keys.FAILED_ATTEMPTS)
            prefs.remove(Keys.LAST_FAILURE_AT)
        }
    }

    /** Whether a recovery kit was generated, so settings can say so honestly. */
    suspend fun hasRecoveryKit(): Boolean = read(Keys.HAS_RECOVERY_KIT) ?: false

    suspend fun setHasRecoveryKit(value: Boolean) {
        context.vaultDataStore.edit { it[Keys.HAS_RECOVERY_KIT] = value }
    }

    /** Wipes every trace of the vault key. Irreversible. */
    suspend fun clearAll() {
        context.vaultDataStore.edit { it.clear() }
    }

    private suspend fun <T> read(key: Preferences.Key<T>): T? =
        context.vaultDataStore.data.first()[key]

    private object Keys {
        val SEALED_CIPHERTEXT = byteArrayPreferencesKey("sealed_vault_key")
        val SEALED_SALT = byteArrayPreferencesKey("sealed_vault_salt")
        val KDF_MEMORY_KIB = intPreferencesKey("kdf_memory_kib")
        val KDF_ITERATIONS = intPreferencesKey("kdf_iterations")
        val KDF_PARALLELISM = intPreferencesKey("kdf_parallelism")
        val KDF_OUTPUT_BYTES = intPreferencesKey("kdf_output_bytes")

        val BIOMETRIC_CIPHERTEXT = byteArrayPreferencesKey("biometric_sealed_key")
        val BIOMETRIC_IV = byteArrayPreferencesKey("biometric_iv")

        val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        val LAST_FAILURE_AT = longPreferencesKey("last_failure_at")

        val HAS_RECOVERY_KIT = booleanPreferencesKey("has_recovery_kit")
    }
}
