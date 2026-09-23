package dev.creds.vault.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.data.prefs.AuditPreferences
import dev.creds.vault.core.data.prefs.AuditSettings
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.audit.AuditFinding
import dev.creds.vault.core.domain.audit.AuditIssue
import dev.creds.vault.core.domain.audit.AuditReport
import dev.creds.vault.core.domain.audit.AuditRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The password health dashboard.
 *
 * The report itself is read-only and built without decrypting anything; this adds the
 * online-check setting, the coordinator's progress, and the one fix that can be applied
 * in place — switching a website to https.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AuditViewModel @Inject constructor(
    private val vaultManager: VaultManager,
    private val preferences: AuditPreferences,
    private val coordinator: AuditCoordinator,
) : ViewModel() {

    private val selectedIssue = MutableStateFlow<AuditIssue?>(null)

    private val report = vaultManager.repository.flatMapLatest { repository ->
        repository?.audit?.observeReport(clock = System::currentTimeMillis) ?: flowOf(null)
    }

    val state: StateFlow<AuditUiState> = combine(
        report,
        preferences.settings,
        coordinator.status,
        selectedIssue,
    ) { report, settings, status, issue ->
        AuditUiState(report = report, settings = settings, status = status, selectedIssue = issue)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AuditUiState())

    fun selectIssue(issue: AuditIssue?) {
        selectedIssue.value = if (selectedIssue.value == issue) null else issue
    }

    fun setOnlineBreachCheck(enabled: Boolean) {
        viewModelScope.launch { preferences.setOnlineBreachCheck(enabled) }
    }

    fun checkNow() = coordinator.checkNow()

    fun useHttps(finding: AuditFinding) {
        val fieldUid = finding.fieldUid ?: return
        val url = finding.url ?: return
        viewModelScope.launch {
            try {
                vaultManager.withUnlocked { repository, vaultKey ->
                    repository.updateFieldValue(
                        vaultKey,
                        finding.itemUuid,
                        fieldUid,
                        AuditRules.httpsVersion(url),
                        System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (vaultManager.isUnlocked) throw e
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class AuditUiState(
    /** Null until the first report arrives. */
    val report: AuditReport? = null,
    val settings: AuditSettings = AuditSettings(),
    val status: AuditStatus = AuditStatus(),
    val selectedIssue: AuditIssue? = null,
) {
    val visibleFindings: List<AuditFinding>
        get() = report?.findings.orEmpty().filter { selectedIssue == null || it.issue == selectedIssue }
}
