package dev.creds.vault.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.ItemDetailDestination
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.repository.TagResult
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One item, read-only, with the actions that do not need the editor.
 *
 * Re-reads the item whenever the repository signals a change — an edit saved from the
 * editor, a tag renamed elsewhere — and drops it the moment the vault locks. Shared
 * eagerly rather than while subscribed: this screen sits on the back stack under the
 * editor, and a lazily shared state would keep the last decrypted item in memory through
 * a lock that happened while nobody was collecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val uuid = savedStateHandle.toRoute<ItemDetailDestination>().uuid

    private val history = MutableStateFlow<FieldHistoryState?>(null)

    private val _events = Channel<ItemDetailEvent>(Channel.BUFFERED)
    val events: Flow<ItemDetailEvent> = _events.receiveAsFlow()

    private val content: Flow<Content> = vaultManager.repository.flatMapLatest { repository ->
        if (repository == null) return@flatMapLatest flowOf(Content.Locked)

        combine(
            repository.observeItemChanges(uuid).mapLatest { exists ->
                if (exists) read() else Content.Missing
            },
            repository.observeTags(),
        ) { content, tags -> if (content is Content.Loaded) content.copy(allTags = tags) else content }
            .catch { error ->
                // Same reasoning as the vault list: a read cut off by a lock is expected.
                if (vaultManager.repository.value === repository) throw error
                emit(Content.Locked)
            }
    }

    val state: StateFlow<ItemDetailUiState> =
        combine(content, history) { content, history ->
            when (content) {
                Content.Locked -> ItemDetailUiState(loading = true)
                Content.Missing -> ItemDetailUiState(loading = false, missing = true)
                is Content.Loaded -> ItemDetailUiState(
                    loading = false,
                    item = content.item,
                    historyCounts = content.historyCounts,
                    allTags = content.allTags,
                    history = history,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ItemDetailUiState())

    init {
        viewModelScope.launch {
            vaultManager.repository.collect { if (it == null) history.value = null }
        }
    }

    private suspend fun read(): Content =
        vaultManager.withUnlocked { repository, vaultKey ->
            val item = repository.load(vaultKey, uuid) ?: return@withUnlocked Content.Missing
            // Only sensitive fields keep history, so only they are worth a count query.
            val counts = item.fields
                .filter { it.sensitive && !it.deleted }
                .associate { it.uid to repository.fieldHistoryCount(it.uid) }
                .filterValues { it > 0 }
            Content.Loaded(item, counts)
        } ?: Content.Locked

    fun setFavorite(favorite: Boolean) = mutate { repository, _ ->
        repository.setFavorite(uuid, favorite, now())
    }

    fun setArchived(archived: Boolean) = mutate { repository, _ ->
        repository.setArchived(uuid, archived, now())
    }

    fun trash() = mutate { repository, _ ->
        repository.trash(uuid, now())
        _events.send(ItemDetailEvent.Trashed)
    }

    fun restore() = mutate { repository, vaultKey ->
        repository.restore(vaultKey, uuid, now())
    }

    fun deleteForever() = mutate { repository, _ ->
        repository.purge(uuid)
        _events.send(ItemDetailEvent.Deleted)
    }

    fun setTags(tagIds: Set<Long>) = mutate { repository, _ ->
        repository.setItemTags(uuid, tagIds, now())
    }

    fun createTag(name: String, onResult: (TagResult) -> Unit) = mutate { repository, _ ->
        onResult(repository.createTag(name))
    }

    fun openHistory(field: VaultField) {
        history.value = FieldHistoryState(fieldUid = field.uid, fieldLabel = field.label)
        mutate { repository, vaultKey ->
            val entries = repository.fieldHistory(vaultKey, field.uid)
            history.update { if (it?.fieldUid == field.uid) it.copy(loading = false, entries = entries) else it }
        }
    }

    fun dismissHistory() {
        history.value = null
    }

    /** See `VaultListViewModel.mutate`: a tap landing just after a lock is dropped quietly. */
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

    private sealed interface Content {
        data object Locked : Content
        data object Missing : Content
        data class Loaded(
            val item: VaultItem,
            val historyCounts: Map<Long, Int>,
            val allTags: List<Tag> = emptyList(),
        ) : Content
    }
}

data class ItemDetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val item: VaultItem? = null,
    /** Field uid to number of previous values, for fields that have any. */
    val historyCounts: Map<Long, Int> = emptyMap(),
    val allTags: List<Tag> = emptyList(),
    val history: FieldHistoryState? = null,
)

/** Outcomes after which the item is no longer something to look at. */
sealed interface ItemDetailEvent {
    data object Trashed : ItemDetailEvent
    data object Deleted : ItemDetailEvent
}
