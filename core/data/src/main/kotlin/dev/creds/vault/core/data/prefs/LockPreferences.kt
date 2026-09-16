package dev.creds.vault.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lockDataStore: DataStore<Preferences> by preferencesDataStore("lock")

/**
 * The lock policy, as the user has configured it.
 *
 * Defaults match the spec's table. They are deliberately strict — the safe direction for
 * a password vault is that someone has to opt *out* of protection, having read what they
 * are turning off.
 */
@Singleton
class LockPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    val policy: Flow<LockPolicy> = context.lockDataStore.data.map { prefs ->
        LockPolicy(
            lockOnBackground = prefs[Keys.LOCK_ON_BACKGROUND] ?: true,
            backgroundGraceSeconds = prefs[Keys.BACKGROUND_GRACE_SECONDS] ?: DEFAULT_GRACE_SECONDS,
            lockOnScreenOff = prefs[Keys.LOCK_ON_SCREEN_OFF] ?: true,
            idleTimeoutMinutes = prefs[Keys.IDLE_TIMEOUT_MINUTES] ?: DEFAULT_IDLE_MINUTES,
            wipeAfterFailures = prefs[Keys.WIPE_AFTER_FAILURES] ?: 0,
            secureFlag = prefs[Keys.SECURE_FLAG] ?: true,
            biometricUnlock = prefs[Keys.BIOMETRIC_UNLOCK] ?: false,
        )
    }

    suspend fun setLockOnBackground(value: Boolean) = put(Keys.LOCK_ON_BACKGROUND, value)

    suspend fun setBackgroundGraceSeconds(value: Int) =
        put(Keys.BACKGROUND_GRACE_SECONDS, value.coerceIn(0, 600))

    suspend fun setLockOnScreenOff(value: Boolean) = put(Keys.LOCK_ON_SCREEN_OFF, value)

    suspend fun setIdleTimeoutMinutes(value: Int) =
        put(Keys.IDLE_TIMEOUT_MINUTES, value.coerceIn(1, 120))

    /**
     * Number of consecutive failures after which the vault is wiped. 0 disables it.
     *
     * Floored at 5 when enabled: a threshold of one or two turns a child prodding at a
     * phone into permanent data loss, and there is no recovery from this.
     */
    suspend fun setWipeAfterFailures(value: Int) =
        put(Keys.WIPE_AFTER_FAILURES, if (value <= 0) 0 else value.coerceIn(5, 100))

    suspend fun setSecureFlag(value: Boolean) = put(Keys.SECURE_FLAG, value)

    suspend fun setBiometricUnlock(value: Boolean) = put(Keys.BIOMETRIC_UNLOCK, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.lockDataStore.edit { it[key] = value }
    }

    private object Keys {
        val LOCK_ON_BACKGROUND = booleanPreferencesKey("lock_on_background")
        val BACKGROUND_GRACE_SECONDS = intPreferencesKey("background_grace_seconds")
        val LOCK_ON_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        val IDLE_TIMEOUT_MINUTES = intPreferencesKey("idle_timeout_minutes")
        val WIPE_AFTER_FAILURES = intPreferencesKey("wipe_after_failures")
        val SECURE_FLAG = booleanPreferencesKey("secure_flag")
        val BIOMETRIC_UNLOCK = booleanPreferencesKey("biometric_unlock")
    }

    companion object {
        /** Long enough for an autofill round-trip to another app and back. */
        const val DEFAULT_GRACE_SECONDS: Int = 60
        const val DEFAULT_IDLE_MINUTES: Int = 5
    }
}

/**
 * A snapshot of the lock policy.
 *
 * @param backgroundGraceSeconds why this exists at all: autofill bounces the user out to
 *   another app and straight back, and locking on that round-trip would make autofill
 *   unusable.
 * @param wipeAfterFailures 0 when disabled, which is the default. Available because some
 *   people genuinely want it; off because irreversible data loss should never be a
 *   surprise.
 */
data class LockPolicy(
    val lockOnBackground: Boolean = true,
    val backgroundGraceSeconds: Int = LockPreferences.DEFAULT_GRACE_SECONDS,
    val lockOnScreenOff: Boolean = true,
    val idleTimeoutMinutes: Int = LockPreferences.DEFAULT_IDLE_MINUTES,
    val wipeAfterFailures: Int = 0,
    val secureFlag: Boolean = true,
    val biometricUnlock: Boolean = false,
) {
    val idleTimeoutMillis: Long get() = idleTimeoutMinutes * 60_000L
    val backgroundGraceMillis: Long get() = backgroundGraceSeconds * 1_000L
    val wipeEnabled: Boolean get() = wipeAfterFailures > 0
}
