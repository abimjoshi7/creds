package dev.creds.vault.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.ItemEditorDestination
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Creates or edits one item.
 *
 * The draft is plaintext, so it follows the vault's lifetime rather than the screen's: a
 * lock wipes it, and the next unlock reads the item afresh. Unsaved edits are lost to a
 * lock by design — the ViewModel outlives the lock on the back stack, and a draft that
 * survived would be secrets held in memory while the vault claims to be closed.
 */
@HiltViewModel
class ItemEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ItemEditorDestination>()

    private val _state = MutableStateFlow(ItemEditorUiState(loading = true))
    val state: StateFlow<ItemEditorUiState> = _state.asStateFlow()

    private var createdAt: Long = 0L
    private var savedUuid: String? = route.uuid
    private var nextAddedKey = 0

    init {
        viewModelScope.launch {
            vaultManager.repository.collect { repository ->
                if (repository == null) {
                    _state.value = ItemEditorUiState(loading = true)
                } else if (_state.value.loading) {
                    load()
                }
            }
        }
    }

    private suspend fun load() {
        val uuid = savedUuid
        if (uuid == null) {
            val template = Template.entries.firstOrNull { it.id == route.templateId } ?: Template.LOGIN
            val now = System.currentTimeMillis()
            createdAt = now
            val fields = TemplateCatalog.newFields(template, now).map { EditableField.from(it) }
            _state.value = ItemEditorUiState(
                isNew = true,
                template = template,
                fields = fields,
            ).withBaseline()
            return
        }

        val item = vaultManager.withUnlocked { repository, vaultKey -> repository.load(vaultKey, uuid) }
        if (item == null) {
            // Null from a lock rather than a missing row: stay loading, the next unlock retries.
            _state.value = ItemEditorUiState(loading = !vaultManager.isUnlocked, missing = vaultManager.isUnlocked)
            return
        }
        createdAt = item.createdAt
        _state.value = ItemEditorUiState(
            isNew = false,
            template = item.template,
            title = item.title,
            note = item.note,
            favorite = item.favorite,
            fields = item.fields
                .filter { !it.deleted }
                .sortedBy { it.order }
                .map { EditableField.from(it) },
        ).withBaseline()
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, error = null) }

    fun onNoteChange(value: String) = _state.update { it.copy(note = value) }

    fun onFieldChange(key: String, value: String) = _state.update { state ->
        state.copy(
            fields = state.fields.map { if (it.key == key) it.copy(value = value) else it },
            error = null,
        )
    }

    fun toggleFavorite() = _state.update { it.copy(favorite = !it.favorite) }

    fun addField(kind: CustomFieldKind, label: String) = _state.update { state ->
        val field = kind.newField(key = "added:${nextAddedKey++}", label = label, now = System.currentTimeMillis())
        state.copy(fields = state.fields + field)
    }

    /**
     * Removes a row from the draft. Nothing is deleted until Save, and a saved field is
     * then tombstoned rather than erased, with its history kept alongside it.
     */
    fun removeField(key: String) = _state.update { state ->
        state.copy(fields = state.fields.filterNot { it.key == key })
    }

    /** [onSaved] receives the item's uuid, which a new item only has once saved. */
    fun save(onSaved: (uuid: String) -> Unit) {
        val current = _state.value
        val title = current.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(error = "Give this item a title") }
            return
        }
        if (current.busy) return

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val uuid = vaultManager.withUnlocked { repository, vaultKey ->
                    persist(repository, vaultKey, current, title)
                }
                if (uuid != null) {
                    savedUuid = uuid
                    // Re-read rather than keep the draft: new fields only have uids once
                    // stored, and a second save of uid-0 rows would duplicate them.
                    load()
                    onSaved(uuid)
                } else {
                    _state.update { it.copy(busy = false, error = "Vault is locked") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "Could not save") }
            }
        }
    }

    private suspend fun persist(
        repository: VaultRepository,
        vaultKey: VaultKey,
        current: ItemEditorUiState,
        title: String,
    ): String {
        val now = System.currentTimeMillis()
        val uuid = savedUuid ?: UUID.randomUUID().toString()
        val fields = current.fields.toVaultFields(current.baseline?.fields.orEmpty(), now)

        // The editor does not show tags, archive or trash state, so they come from what is
        // stored now. Saving must never quietly untag, unarchive or restore an item.
        val stored = repository.load(vaultKey, uuid)

        repository.save(
            vaultKey,
            VaultItem(
                uuid = uuid,
                template = current.template,
                title = title,
                subtitle = TemplateCatalog.subtitleFor(current.template, fields),
                note = current.note,
                icon = stored?.icon,
                favorite = current.favorite,
                archived = stored?.archived ?: false,
                trashed = stored?.trashed ?: false,
                createdAt = createdAt,
                updatedAt = now,
                fields = fields,
                tags = stored?.tags.orEmpty(),
            ),
        )
        return uuid
    }
}

data class ItemEditorUiState(
    val loading: Boolean = false,
    val missing: Boolean = false,
    val isNew: Boolean = false,
    val busy: Boolean = false,
    val template: Template = Template.LOGIN,
    val title: String = "",
    val note: String = "",
    val favorite: Boolean = false,
    val fields: List<EditableField> = emptyList(),
    val error: String? = null,
    /** The draft as loaded or last saved; null until there is one. */
    val baseline: EditorSnapshot? = null,
) {
    val canSave: Boolean get() = !busy && !loading && !missing && title.isNotBlank()

    val snapshot: EditorSnapshot get() = EditorSnapshot(title, note, favorite, fields)

    /** Whether leaving now would throw away something the user typed. */
    val isDirty: Boolean get() = baseline != null && snapshot != baseline

    fun withBaseline(): ItemEditorUiState = copy(baseline = snapshot)
}
