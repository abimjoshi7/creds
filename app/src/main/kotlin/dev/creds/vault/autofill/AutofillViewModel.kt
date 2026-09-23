package dev.creds.vault.autofill

import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.autofill.AutofillCandidate
import dev.creds.vault.core.domain.autofill.AutofillTrust
import dev.creds.vault.core.domain.autofill.CapturedLogin
import dev.creds.vault.core.domain.autofill.FillOrigin
import dev.creds.vault.core.domain.autofill.FormKind
import dev.creds.vault.core.domain.autofill.SavePlanner
import dev.creds.vault.core.domain.autofill.TrustVerdict
import dev.creds.vault.core.model.Template
import dev.creds.vault.feature.autofill.AutofillAnalysis
import dev.creds.vault.feature.autofill.AutofillResponses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** What the autofill activity was started to do. */
sealed interface AutofillRequest {
    /** Unlock, then answer the original fill request as the service would have. */
    data class Unlock(val analysis: AutofillAnalysis) : AutofillRequest

    /** Let the user choose an item, confirming trust where it has not been given before. */
    data class Pick(val analysis: AutofillAnalysis) : AutofillRequest

    data class Save(val origin: FillOrigin, val originName: String, val captured: CapturedLogin) : AutofillRequest
}

/**
 * The autofill activity's work, after the vault is open.
 *
 * Every decision about whether an item may fill is `AutofillTrust`'s; this only carries it
 * out — recording trust once the user has confirmed it, and refusing a conflict outright.
 */
@HiltViewModel
class AutofillViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val _picker = MutableStateFlow(AutofillPickerUiState())
    val picker: StateFlow<AutofillPickerUiState> = _picker.asStateFlow()

    private val _save = MutableStateFlow(AutofillSaveUiState())
    val save: StateFlow<AutofillSaveUiState> = _save.asStateFlow()

    /** The response the service would have given had the vault been open. */
    suspend fun unlockedResponse(analysis: AutofillAnalysis): FillResponse? = withContext(Dispatchers.Default) {
        vaultManager.withUnlocked { repository, vaultKey ->
            AutofillResponses.unlocked(context, analysis, repository, vaultKey, inline = null, now = now())
        }
    }

    fun loadPicker(analysis: AutofillAnalysis) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                vaultManager.withUnlocked { repository, _ -> repository.autofillCandidates() }
            } ?: return@launch
            val rows = loaded.mapNotNull { row(it, analysis) }
                // Suggested first, then everything else; refused items last.
                .sortedWith(compareBy({ it.verdict.sortOrder }, { it.title.lowercase() }))
            _picker.update { it.copy(originName = analysis.displayName, isWeb = analysis.origin is FillOrigin.Web, rows = rows) }
        }
    }

    fun onQueryChange(query: String) = _picker.update { it.copy(query = query) }

    /**
     * The user chose [uuid]. Trusted items fill at once; a never-confirmed one asks first; a
     * conflicting one is refused with an explanation.
     */
    fun pick(analysis: AutofillAnalysis, uuid: String, confirmed: Boolean, onDataset: (Dataset) -> Unit) {
        val row = _picker.value.rows.orEmpty().firstOrNull { it.uuid == uuid } ?: return
        when {
            row.verdict == PickVerdict.CONFLICT -> _picker.update { it.copy(refused = row) }
            row.verdict == PickVerdict.UNCONFIRMED && !confirmed -> _picker.update { it.copy(confirm = row) }
            else -> viewModelScope.launch {
                val dataset = withContext(Dispatchers.Default) {
                    vaultManager.withUnlocked { repository, vaultKey ->
                        if (row.verdict == PickVerdict.UNCONFIRMED) {
                            repository.recordAssociation(uuid, AutofillTrust.associationFor(analysis.origin, now()), now())
                        }
                        repository.load(vaultKey, uuid)?.let { AutofillResponses.dataset(context, analysis, it, now()) }
                    }
                }
                if (dataset != null) {
                    onDataset(dataset)
                } else {
                    _picker.update { it.copy(confirm = null, message = "“${row.title}” has nothing to fill here.") }
                }
            }
        }
    }

    fun dismissDialogs() = _picker.update { it.copy(confirm = null, refused = null, message = null) }

    fun loadSave(request: AutofillRequest.Save) {
        viewModelScope.launch {
            val matches = withContext(Dispatchers.Default) {
                vaultManager.withUnlocked { repository, vaultKey ->
                    repository.autofillCandidates()
                        .filter { AutofillTrust.verdict(request.origin, it) == TrustVerdict.TRUSTED }
                        .mapNotNull { repository.load(vaultKey, it.uuid) }
                        .filter { item ->
                            item.primaryPassword != null &&
                                (item.primaryUsername?.value ?: "").equals(request.captured.username.orEmpty(), ignoreCase = true)
                        }
                        .map { SaveMatch(it.uuid, it.title) }
                }
            }.orEmpty()
            _save.value = AutofillSaveUiState(
                loaded = true,
                originName = request.originName,
                title = request.originName,
                username = request.captured.username.orEmpty(),
                matches = matches,
                fingerprint = (request.origin as? FillOrigin.App)?.let { fingerprint(it.certKey) },
            )
        }
    }

    fun onTitleChange(title: String) = _save.update { it.copy(title = title) }

    /** Saves a new item, or updates the password of [updateUuid] keeping the old one in history. */
    fun saveLogin(request: AutofillRequest.Save, updateUuid: String?, onDone: () -> Unit) {
        if (_save.value.busy) return
        _save.update { it.copy(busy = true) }
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                vaultManager.withUnlocked { repository, vaultKey ->
                    val now = now()
                    if (updateUuid != null) {
                        val password = repository.load(vaultKey, updateUuid)?.primaryPassword ?: return@withUnlocked
                        repository.updateFieldValue(vaultKey, updateUuid, password.uid, request.captured.password, now)
                    } else {
                        val uuid = UUID.randomUUID().toString()
                        val website = (request.origin as? FillOrigin.Web)?.let { "https://${it.host}" }
                        val title = _save.value.title.trim().ifEmpty { request.originName }
                        repository.save(vaultKey, SavePlanner.newItem(uuid, request.captured, title, website, now))
                        // Saving from an app is the user vouching for it, so it fills there next time.
                        if (request.origin is FillOrigin.App) {
                            repository.recordAssociation(uuid, AutofillTrust.associationFor(request.origin, now), now)
                        }
                    }
                }
            }
            onDone()
        }
    }

    suspend fun isInitialised(): Boolean = vaultManager.isInitialised()

    private fun row(candidate: AutofillCandidate, analysis: AutofillAnalysis): PickerRow? {
        val kinds = analysis.kinds
        val credential = FormKind.LOGIN in kinds || FormKind.OTP in kinds
        val template = candidate.template
        val verdict = when {
            template == Template.CARD && FormKind.CARD in kinds -> PickVerdict.NOT_NEEDED
            template == Template.IDENTITY && FormKind.IDENTITY in kinds -> PickVerdict.NOT_NEEDED
            !credential -> return null
            else -> when (AutofillTrust.verdict(analysis.origin, candidate)) {
                TrustVerdict.TRUSTED -> PickVerdict.TRUSTED
                TrustVerdict.UNKNOWN -> PickVerdict.UNCONFIRMED
                TrustVerdict.CONFLICT -> PickVerdict.CONFLICT
            }
        }
        return PickerRow(candidate.uuid, candidate.title, candidate.subtitle, template, verdict)
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** The first 32 hex characters of a signing key, grouped in fours: enough to compare. */
        fun fingerprint(certKey: String): String =
            certKey.substringBefore(',').take(32).chunked(4).joinToString(" ") + "…"
    }
}

enum class PickVerdict(val sortOrder: Int) { TRUSTED(0), NOT_NEEDED(1), UNCONFIRMED(2), CONFLICT(3) }

data class PickerRow(
    val uuid: String,
    val title: String,
    val subtitle: String,
    val template: Template,
    val verdict: PickVerdict,
)

data class AutofillPickerUiState(
    val originName: String = "",
    val isWeb: Boolean = false,
    val query: String = "",
    /** Null while loading. */
    val rows: List<PickerRow>? = null,
    val confirm: PickerRow? = null,
    val refused: PickerRow? = null,
    val message: String? = null,
) {
    val visibleRows: List<PickerRow>
        get() = rows.orEmpty().filter {
            query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.subtitle.contains(query.trim(), ignoreCase = true)
        }
}

data class SaveMatch(val uuid: String, val title: String)

data class AutofillSaveUiState(
    val loaded: Boolean = false,
    val originName: String = "",
    val title: String = "",
    val username: String = "",
    val matches: List<SaveMatch> = emptyList(),
    /** Set when saving from an app: the key it will be trusted by. */
    val fingerprint: String? = null,
    val busy: Boolean = false,
)
