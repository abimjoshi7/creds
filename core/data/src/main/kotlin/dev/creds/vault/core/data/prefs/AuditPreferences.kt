package dev.creds.vault.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.auditDataStore: DataStore<Preferences> by preferencesDataStore("audit")

/**
 * Audit settings and bookkeeping. Nothing secret: whether the online check is on, when it
 * last succeeded, and which version of the scoring rules the cached results came from.
 */
@Singleton
class AuditPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    val settings: Flow<AuditSettings> = context.auditDataStore.data.map { prefs ->
        AuditSettings(
            // Off unless the user turns it on. The offline check needs no consent; this does.
            onlineBreachCheck = prefs[Keys.ONLINE_BREACH_CHECK] ?: false,
            lastOnlineCheckAt = prefs[Keys.LAST_ONLINE_CHECK_AT],
            resultsVersion = prefs[Keys.RESULTS_VERSION] ?: 0,
        )
    }

    suspend fun setOnlineBreachCheck(enabled: Boolean) {
        context.auditDataStore.edit { prefs ->
            prefs[Keys.ONLINE_BREACH_CHECK] = enabled
            if (!enabled) prefs.remove(Keys.LAST_ONLINE_CHECK_AT)
        }
    }

    suspend fun setLastOnlineCheckAt(at: Long) {
        context.auditDataStore.edit { it[Keys.LAST_ONLINE_CHECK_AT] = at }
    }

    suspend fun setResultsVersion(version: Int) {
        context.auditDataStore.edit { it[Keys.RESULTS_VERSION] = version }
    }

    private object Keys {
        val ONLINE_BREACH_CHECK = booleanPreferencesKey("online_breach_check")
        val LAST_ONLINE_CHECK_AT = longPreferencesKey("last_online_check_at")
        val RESULTS_VERSION = intPreferencesKey("results_version")
    }
}

data class AuditSettings(
    val onlineBreachCheck: Boolean = false,
    val lastOnlineCheckAt: Long? = null,
    val resultsVersion: Int = 0,
)
