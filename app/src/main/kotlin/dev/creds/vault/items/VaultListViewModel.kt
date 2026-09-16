package dev.creds.vault.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.repository.TagResult
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultCounts
import dev.creds.vault.core.model.VaultFilter
import dev.creds.vault.core.model.VaultItemSummary
import dev.creds.vault.debug.SampleVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The vault list: what is shown, and the list-level actions on it.
 *
 * Everything is derived from [VaultManager.repository] rather than from a repository
 * captured once. A lock can land at any moment; when it does the repository flow goes
 * null, the queries are cancelled, and the rows held here are dropped — the list does not
 * keep titles in memory after the vault closes.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val filter = MutableStateFlow(VaultFilter())

    /** Held apart from [filter] so typing is debounced but chip taps are not. */
    private val query = MutableStateFlow("")

    private val _events = Channel<VaultListEvent>(Channel.BUFFERED)
    val events: Flow<VaultListEvent> = _events.receiveAsFlow()

    private val effectiveFilter: Flow<VaultFilter> = combine(
        filter,
        // Clearing the box applies at once; only typing waits for a pause.
        query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
    ) { filter, query -> filter.copy(query = query) }
        .distinctUntilChanged()

    private val content: Flow<VaultContent?> = vaultManager.repository.flatMapLatest { repository ->
        if (repository == null) return@flatMapLatest flowOf(null)

        combine<List<VaultItemSummary>, List<Tag>, VaultCounts, VaultContent?>(
            effectiveFilter.flatMapLatest(repository::observeItems),
            repository.observeTags().onEach { tags ->
                val ids = tags.mapTo(HashSet(), Tag::id)
                filter.update { it.retainingTags(ids) }
            },
            repository.observeCounts(),
        ) { items, tags, counts -> VaultContent(items, tags, counts) }
            .catch { error ->
                // A query cut off by a lock fails against a closed database. That is
                // expected once this repository is no longer the open one; anything
                // else is a real bug and should stay loud.
                if (vaultManager.repository.value === repository) throw error
                emit(null)
            }
    }

    val state: StateFlow<VaultListUiState> =
        combine(filter, query, content) { filter, query, content ->
            VaultListUiState(
                filter = filter.copy(query = query),
                items = content?.items,
                tags = content?.tags.orEmpty(),
                counts = content?.counts ?: VaultCounts(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), VaultListUiState())

    init {
        // A search or filter left over from before a lock is not something to greet the
        // next unlock with — least of all on a shared screen.
        viewModelScope.launch {
            vaultManager.repository.collect { if (it == null) resetFilters() }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun selectSmartList(smartList: SmartList) {
        filter.update { it.copy(smartList = smartList) }
    }

    fun toggleTemplate(template: Template) {
        filter.update { it.withTemplateToggled(template) }
    }

    fun toggleTag(tagId: Long) {
        filter.update { it.withTagToggled(tagId) }
    }

    fun resetFilters() {
        filter.value = VaultFilter()
        query.value = ""
    }

    fun setFavorite(item: VaultItemSummary, favorite: Boolean) = mutate { repository, _ ->
        repository.setFavorite(item.uuid, favorite, now())
    }

    fun archive(item: VaultItemSummary) = mutate { repository, _ ->
        repository.setArchived(item.uuid, archived = true, now())
        _events.send(VaultListEvent.Archived(item))
    }

    fun unarchive(item: VaultItemSummary) = mutate { repository, _ ->
        repository.setArchived(item.uuid, archived = false, now())
    }

    fun trash(item: VaultItemSummary) = mutate { repository, _ ->
        repository.trash(item.uuid, now())
        _events.send(VaultListEvent.Trashed(item))
    }

    /** Needs the key: restoring re-indexes the item, which reads its encrypted note. */
    fun restore(item: VaultItemSummary) = mutate { repository, vaultKey ->
        repository.restore(vaultKey, item.uuid, now())
    }

    fun deleteForever(item: VaultItemSummary) = mutate { repository, _ ->
        repository.purge(item.uuid)
    }

    fun emptyTrash() = mutate { repository, _ ->
        _events.send(VaultListEvent.TrashEmptied(repository.emptyTrash()))
    }

    fun setTags(item: VaultItemSummary, tagIds: Set<Long>) = mutate { repository, _ ->
        repository.setItemTags(item.uuid, tagIds, now())
    }

    /** For creating a tag from inside the tag picker. [onResult] runs on the main thread. */
    fun createTag(name: String, onResult: (TagResult) -> Unit) = mutate { repository, _ ->
        onResult(repository.createTag(name))
    }

    val canAddSamples: Boolean get() = SampleVault.AVAILABLE

    fun addSamples() = mutate { repository, vaultKey ->
        _events.send(VaultListEvent.SamplesAdded(SampleVault.seed(repository, vaultKey, now())))
    }

    /**
     * Runs a user action against the open vault.
     *
     * A tap that lands just after an idle lock is ordinary: the action is dropped, and an
     * operation a lock cuts off part-way fails quietly. The same failure while the vault
     * is still open is a bug and is rethrown.
     */
    private fun mutate(block: suspend (VaultRepository, VaultKey) -> Unit) {
        viewModelScope.launch {
            try {
                vaultManager.withUnlocked(block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (vaultManager.isUnlocked) throw e
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private data class VaultContent(
        val items: List<VaultItemSummary>,
        val tags: List<Tag>,
        val counts: VaultCounts,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class VaultListUiState(
    val filter: VaultFilter = VaultFilter(),
    /** Null until the first query returns, so an empty-vault message never flashes. */
    val items: List<VaultItemSummary>? = null,
    val tags: List<Tag> = emptyList(),
    val counts: VaultCounts = VaultCounts(),
) {
    /** The tags currently narrowing the list, in display order. */
    val activeTags: List<Tag> get() = tags.filter { it.id in filter.tagIds }
}

/** One-off outcomes the screen reports, some with an undo. */
sealed interface VaultListEvent {
    data class Trashed(val item: VaultItemSummary) : VaultListEvent
    data class Archived(val item: VaultItemSummary) : VaultListEvent
    data class TrashEmptied(val count: Int) : VaultListEvent
    data class SamplesAdded(val count: Int) : VaultListEvent
}
