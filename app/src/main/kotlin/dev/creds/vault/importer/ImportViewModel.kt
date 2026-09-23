package dev.creds.vault.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.importer.EnpassParser
import dev.creds.vault.core.domain.importer.ImportException
import dev.creds.vault.core.domain.importer.ImportPlanner
import dev.creds.vault.core.domain.importer.ImportRow
import dev.creds.vault.core.domain.importer.ImportStatus
import dev.creds.vault.core.domain.importer.ImportWarnings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Enpass import: read, preview, commit.
 *
 * The export is plaintext, so it is handled like any other secret: read straight from the
 * document provider into memory — never copied to a file, not even a cache file — parsed
 * there, and dropped as soon as the import is written, cancelled, or the vault locks.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val vaultManager: VaultManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            vaultManager.repository.collect { repository ->
                if (repository == null && _state.value.stage != ImportStage.CHOOSE) {
                    _state.value = ImportUiState(error = "The vault locked, so the file was closed. Choose it again to continue.")
                }
            }
        }
    }

    fun onFileChosen(uri: Uri) {
        _state.value = ImportUiState(stage = ImportStage.READING)
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                val parsed = withContext(Dispatchers.Default) { EnpassParser.parse(text) }
                val existing = withContext(Dispatchers.Default) {
                    vaultManager.withUnlocked { repository, vaultKey -> repository.importKeys(vaultKey) }
                } ?: return@launch
                val rows = ImportPlanner.plan(parsed, existing) { UUID.randomUUID().toString() }
                _state.value = ImportUiState(stage = ImportStage.PREVIEW, rows = rows, warnings = parsed.warnings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                _state.value = ImportUiState(error = e.message)
            } catch (_: IOException) {
                _state.value = ImportUiState(error = "The file could not be read.")
            } catch (_: SecurityException) {
                // The provider revoked access between choosing and reading.
                _state.value = ImportUiState(error = "The file could not be read.")
            }
        }
    }

    fun toggle(index: Int) = _state.update { state ->
        state.copy(rows = state.rows.mapIndexed { i, row -> if (i == index) row.copy(selected = !row.selected) else row })
    }

    fun commit() {
        val current = _state.value
        val chosen = current.rows.filter { it.selected }.map { it.imported }
        if (current.stage != ImportStage.PREVIEW || chosen.isEmpty()) return
        _state.update { it.copy(stage = ImportStage.IMPORTING) }
        viewModelScope.launch {
            try {
                val done = withContext(Dispatchers.Default) {
                    vaultManager.withUnlocked { repository, vaultKey -> repository.importItems(vaultKey, chosen) }
                }
                // Written or not, the plaintext preview is dropped.
                _state.value = if (done != null) {
                    ImportUiState(stage = ImportStage.DONE, imported = chosen.size)
                } else {
                    ImportUiState(error = "The vault locked before the import was written. Nothing was imported.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!vaultManager.isUnlocked) {
                    _state.value = ImportUiState(error = "The vault locked before the import was written. Nothing was imported.")
                } else {
                    throw e
                }
            }
        }
    }

    fun reset() {
        _state.value = ImportUiState()
    }

    /** Reads at most [MAX_BYTES]; an Enpass export of that size would be tens of thousands of items. */
    private fun readText(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream")
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        try {
            input.use {
                while (true) {
                    val read = it.read(chunk)
                    if (read < 0) break
                    if (buffer.size() + read > MAX_BYTES) throw ImportException("This file is too large to be an Enpass export.")
                    buffer.write(chunk, 0, read)
                }
            }
            val bytes = buffer.toByteArray()
            return try {
                bytes.decodeToString()
            } finally {
                bytes.wipe()
            }
        } finally {
            chunk.wipe()
            // ByteArrayOutputStream cannot be wiped in place; reset() at least drops its count.
            buffer.reset()
        }
    }

    private companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}

enum class ImportStage { CHOOSE, READING, PREVIEW, IMPORTING, DONE }

data class ImportUiState(
    val stage: ImportStage = ImportStage.CHOOSE,
    val rows: List<ImportRow> = emptyList(),
    val warnings: ImportWarnings = ImportWarnings(),
    val imported: Int = 0,
    val error: String? = null,
) {
    val newCount: Int get() = rows.count { it.status == ImportStatus.NEW }
    val duplicateCount: Int get() = rows.count { it.status == ImportStatus.DUPLICATE }
    val selectedCount: Int get() = rows.count { it.selected }
}
