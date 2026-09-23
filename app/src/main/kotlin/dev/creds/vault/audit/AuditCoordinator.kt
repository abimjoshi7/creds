package dev.creds.vault.audit

import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.breach.HibpRangeClient
import dev.creds.vault.core.data.prefs.AuditPreferences
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.breach.OfflineBreachList
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the audit's cached results current while the vault is open.
 *
 * Runs a pass on unlock, after any change to the vault (once edits settle), when the
 * online check is switched on, and on request. A pass scores only what changed, so after
 * the first one it is usually a single query that finds nothing to do. It stops the
 * moment the vault locks; whatever it finished is kept.
 *
 * The online check makes a request only for passwords not yet checked since they last
 * changed, so an unchanged vault causes no network traffic at all, even with it enabled.
 */
@OptIn(FlowPreview::class)
@Singleton
class AuditCoordinator @Inject constructor(
    private val vaultManager: VaultManager,
    private val estimator: PasswordStrengthEstimator,
    private val preferences: AuditPreferences,
    private val hibp: HibpRangeClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(AuditStatus())
    val status: StateFlow<AuditStatus> = _status.asStateFlow()

    private val manualChecks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun start() {
        scope.launch {
            vaultManager.repository.collectLatest { repository ->
                _status.value = AuditStatus()
                if (repository == null) return@collectLatest

                resetIfOutdated(repository)
                merge(
                    repository.audit.observeChanges().debounce(SETTLE_MS).map { Trigger.CHANGE },
                    preferences.settings.map { it.onlineBreachCheck }.distinctUntilChanged().drop(1).map { Trigger.SETTINGS },
                    manualChecks.map { Trigger.MANUAL },
                )
                    // Passes run one at a time; triggers arriving during one collapse into a
                    // single follow-up rather than cancelling work that is nearly done.
                    .conflate()
                    .collect { trigger -> runPass(manual = trigger == Trigger.MANUAL) }
            }
        }
    }

    /** Asks for a pass now, including the online check when it is enabled. */
    fun checkNow() {
        manualChecks.tryEmit(Unit)
    }

    private suspend fun runPass(manual: Boolean) {
        _status.update { it.copy(scoring = true) }
        try {
            guarded { repository, vaultKey ->
                repository.audit.rescore(vaultKey, estimator, OfflineBreachList::contains, now())
            }
        } finally {
            _status.update { it.copy(scoring = false) }
        }

        if (!preferences.settings.first().onlineBreachCheck) {
            _status.update { it.copy(onlineError = null) }
            return
        }

        _status.update { it.copy(checkingOnline = true) }
        try {
            val result = guarded { repository, vaultKey -> repository.audit.checkOnline(vaultKey, hibp, now()) }
            if (result != null && (result.checked > 0 || manual)) preferences.setLastOnlineCheckAt(now())
            _status.update { it.copy(onlineError = null) }
        } catch (_: IOException) {
            _status.update { it.copy(onlineError = "Couldn't reach Have I Been Pwned. Offline checks still apply.") }
        } finally {
            _status.update { it.copy(checkingOnline = false) }
        }
    }

    /**
     * Cached results carry the version of the rules and breach list that produced them. A
     * build that changes either discards them, and the next pass redoes the vault.
     */
    private suspend fun resetIfOutdated(repository: VaultRepository) {
        if (preferences.settings.first().resultsVersion == RESULTS_VERSION) return
        repository.audit.clearResults()
        preferences.setResultsVersion(RESULTS_VERSION)
    }

    /** See `VaultListViewModel.mutate`: failure caused by a lock is expected, anything else is not. */
    private suspend fun <R> guarded(
        block: suspend (VaultRepository, VaultKey) -> R,
    ): R? = try {
        vaultManager.withUnlocked(block)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        if (vaultManager.isUnlocked) throw e
        null
    }

    private fun now(): Long = System.currentTimeMillis()

    private enum class Trigger { CHANGE, SETTINGS, MANUAL }

    companion object {
        /** Bump when scoring rules or the bundled breach list change. */
        const val RESULTS_VERSION: Int = 1

        private const val SETTLE_MS = 750L
    }
}

data class AuditStatus(
    val scoring: Boolean = false,
    val checkingOnline: Boolean = false,
    val onlineError: String? = null,
)
