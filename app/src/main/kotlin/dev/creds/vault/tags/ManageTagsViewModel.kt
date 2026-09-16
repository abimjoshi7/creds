package dev.creds.vault.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.data.repository.TagResult
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.tag.TagNames
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.VaultCounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Creating, renaming, recolouring and deleting tags.
 *
 * Follows the same lock discipline as the vault list: state is derived from the live
 * repository flow, so a lock empties this screen rather than leaving tag names behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ManageTagsViewModel @Inject constructor(
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val editor = MutableStateFlow<TagEditorState?>(null)

    private val content = vaultManager.repository.flatMapLatest { repository ->
        if (repository == null) return@flatMapLatest flowOf(null)

        combine<List<Tag>, VaultCounts, Pair<List<Tag>, Map<Long, Int>>?>(
            repository.observeTags(),
            repository.observeCounts(),
        ) { tags, counts ->
            tags to counts.byTag
        }.catch { error ->
            if (vaultManager.repository.value === repository) throw error
            emit(null)
        }
    }

    val state: StateFlow<ManageTagsUiState> = combine(content, editor) { content, editor ->
        ManageTagsUiState(
            tags = content?.first,
            counts = content?.second.orEmpty(),
            editor = editor,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageTagsUiState())

    init {
        viewModelScope.launch {
            vaultManager.repository.map { it == null }.collect { locked -> if (locked) editor.value = null }
        }
    }

    fun startCreate() {
        editor.value = TagEditorState(tagId = null, name = "", color = null)
    }

    fun startEdit(tag: Tag) {
        editor.value = TagEditorState(tagId = tag.id, name = tag.name, color = tag.color)
    }

    fun onNameChange(name: String) {
        editor.update { it?.copy(name = name, error = null) }
    }

    fun onColorChange(color: Int?) {
        editor.update { it?.copy(color = color) }
    }

    fun dismissEditor() {
        editor.value = null
    }

    fun saveEditor() {
        val current = editor.value ?: return
        if (current.busy) return
        editor.value = current.copy(busy = true, error = null)

        launchUnlocked {
            val result = vaultManager.withUnlocked { repository, _ ->
                if (current.tagId == null) {
                    repository.createTag(current.name, current.color)
                } else {
                    repository.updateTag(Tag(current.tagId, current.name, current.color))
                }
            }
            editor.update { latest ->
                when (result) {
                    null, is TagResult.Saved -> null
                    else -> latest?.copy(busy = false, error = result.message())
                }
            }
        }
    }

    fun delete(tag: Tag) = launchUnlocked {
        vaultManager.withUnlocked { repository, _ ->
            repository.deleteTag(tag.id, System.currentTimeMillis())
        }
    }

    /** See `VaultListViewModel.mutate`: failures a lock caused are expected, others are not. */
    private fun launchUnlocked(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (vaultManager.isUnlocked) throw e
            }
        }
    }
}

data class ManageTagsUiState(
    /** Null until loaded. */
    val tags: List<Tag>? = null,
    val counts: Map<Long, Int> = emptyMap(),
    val editor: TagEditorState? = null,
)

/** The create/rename dialog. [tagId] is null when creating. */
data class TagEditorState(
    val tagId: Long?,
    val name: String,
    val color: Int?,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = tagId == null
    val canSave: Boolean get() = !busy && TagNames.normalize(name).isNotEmpty()
}

/** What to tell a person when a tag could not be saved. */
fun TagResult.message(): String? = when (this) {
    is TagResult.Saved -> null
    TagResult.Blank -> "Enter a name"
    is TagResult.TooLong -> "Use $max characters or fewer"
    is TagResult.Duplicate -> "“$existing” already exists"
    TagResult.NotFound -> "This tag was deleted"
}
