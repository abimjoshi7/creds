package dev.creds.vault.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.ItemDestination
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.FieldHistoryEntry
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ItemEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ItemDestination>()

    private val _state = MutableStateFlow(ItemEditorUiState(loading = true))
    val state: StateFlow<ItemEditorUiState> = _state.asStateFlow()

    private var createdAt: Long = 0L
    private var originalUuid: String? = route.uuid
    private var initialValues: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val uuid = route.uuid
        if (uuid == null) {
            val template = Template.entries.firstOrNull { it.id == route.templateId } ?: Template.LOGIN
            val now = System.currentTimeMillis()
            createdAt = now
            val fields = TemplateCatalog.newFields(template, now).map { EditableField.from(it) }
            initialValues = fields.associate { it.key to it.value }
            _state.value = ItemEditorUiState(
                loading = false,
                isNew = true,
                template = template,
                title = "",
                note = "",
                fields = fields,
            )
            return
        }

        val loaded = vaultManager.withUnlocked { repository, vaultKey ->
            val item = repository.load(vaultKey, uuid) ?: return@withUnlocked null
            val counts = item.fields.associate { field ->
                field.uid to repository.fieldHistoryCount(field.uid)
            }
            item to counts
        }
        if (loaded == null) {
            _state.value = ItemEditorUiState(loading = false, missing = true)
            return
        }
        val (item, counts) = loaded
        createdAt = item.createdAt
        originalUuid = item.uuid
        val fields = item.fields
            .filter { !it.deleted && it.type.isValueBearing }
            .sortedBy { it.order }
            .map { EditableField.from(it, historyCount = counts[it.uid] ?: 0) }
        initialValues = fields.associate { it.key to it.value }
        _state.value = ItemEditorUiState(
            loading = false,
            isNew = false,
            template = item.template,
            title = item.title,
            note = item.note,
            favorite = item.favorite,
            fields = fields,
        )
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

    fun openHistory(fieldKey: String) {
        val field = _state.value.fields.firstOrNull { it.key == fieldKey } ?: return
        if (field.uid == 0L || field.historyCount == 0) return
        _state.update { it.copy(historyLoading = true, historyFieldKey = fieldKey, history = emptyList()) }
        viewModelScope.launch {
            val entries = vaultManager.withUnlocked { repository, vaultKey ->
                repository.fieldHistory(vaultKey, field.uid)
            }.orEmpty()
            _state.update {
                it.copy(historyLoading = false, history = entries, historyFieldKey = fieldKey)
            }
        }
    }

    fun dismissHistory() = _state.update {
        it.copy(historyFieldKey = null, history = emptyList(), historyLoading = false)
    }

    fun save(onSaved: () -> Unit) {
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
                val ok = vaultManager.withUnlocked { repository, vaultKey ->
                    persist(repository, vaultKey, current, title)
                    true
                } == true
                if (ok) onSaved() else _state.update { it.copy(busy = false, error = "Vault is locked") }
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
    ) {
        val now = System.currentTimeMillis()
        val uuid = originalUuid ?: UUID.randomUUID().toString()
        val fields = current.fields.mapIndexed { index, field ->
            val valueChanged = initialValues[field.key] != field.value
            field.toVaultField(
                order = index,
                now = now,
                valueUpdatedAt = when {
                    field.uid == 0L -> now
                    valueChanged -> now
                    else -> field.valueUpdatedAt
                },
            )
        }
        repository.save(
            vaultKey,
            VaultItem(
                uuid = uuid,
                template = current.template,
                title = title,
                subtitle = TemplateCatalog.subtitleFor(current.template, fields),
                note = current.note,
                favorite = current.favorite,
                createdAt = if (current.isNew) now else createdAt,
                updatedAt = now,
                fields = fields,
            ),
        )
        originalUuid = uuid
    }
}

data class EditableField(
    val key: String,
    val uid: Long,
    val type: FieldType,
    val label: String,
    val value: String,
    val sensitive: Boolean,
    val valueUpdatedAt: Long,
    val historyCount: Int = 0,
) {
    fun toVaultField(order: Int, now: Long, valueUpdatedAt: Long): VaultField = VaultField(
        uid = uid,
        type = type,
        label = label,
        value = value,
        sensitive = sensitive,
        order = order,
        updatedAt = now,
        valueUpdatedAt = valueUpdatedAt,
    )

    companion object {
        fun from(field: VaultField, historyCount: Int = 0) = EditableField(
            key = if (field.uid != 0L) "uid:${field.uid}" else "new:${field.order}:${field.label}",
            uid = field.uid,
            type = field.type,
            label = field.label,
            value = field.value,
            sensitive = field.sensitive,
            valueUpdatedAt = field.valueUpdatedAt,
            historyCount = historyCount,
        )
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
    val historyFieldKey: String? = null,
    val historyLoading: Boolean = false,
    val history: List<FieldHistoryEntry> = emptyList(),
) {
    val canSave: Boolean get() = !busy && !loading && !missing && title.isNotBlank()

    val historyField: EditableField?
        get() = historyFieldKey?.let { key -> fields.firstOrNull { it.key == key } }
}
