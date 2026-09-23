package dev.creds.vault.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.backup.BackupException
import dev.creds.vault.core.data.backup.OpenedBackup
import dev.creds.vault.core.data.backup.VaultBackup
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
 * Import: read, preview, commit — from an Enpass JSON export or a Vaultesque `.vault`
 * backup, told apart by the file's first bytes.
 *
 * An Enpass export is plaintext, so it is handled like any other secret: read straight
 * from the document provider into memory — never copied to a file, not even a cache
 * file — parsed there, and dropped as soon as the import is written, cancelled, or the
 * vault locks. A backup is opened with its own password; its file key is held until the
 * import is written or abandoned, and its attachments are streamed from the file again
 * as the import is written, one at a time.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val vaultManager: VaultManager,
    private val vaultBackup: VaultBackup,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** The chosen backup, while its password is asked for and its preview is shown. */
    private var backupUri: Uri? = null
    private var opened: OpenedBackup? = null

    init {
        viewModelScope.launch {
            vaultManager.repository.collect { repository ->
                if (repository == null && _state.value.stage != ImportStage.CHOOSE) {
                    closeBackup()
                    _state.value = ImportUiState(error = "The vault locked, so the file was closed. Choose it again to continue.")
                }
            }
        }
    }

    fun onFileChosen(uri: Uri) {
        closeBackup()
        _state.value = ImportUiState(stage = ImportStage.READING)
        viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { isBackup(uri) }) {
                    backupUri = uri
                    _state.value = ImportUiState(stage = ImportStage.PASSWORD, source = ImportSource.BACKUP)
                    return@launch
                }
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

    fun onBackupPasswordChange(value: String) = _state.update { it.copy(backupPassword = value, error = null) }

    /** Checks the backup's password and reads its contents for the preview. */
    fun openBackup() {
        val uri = backupUri ?: return
        val current = _state.value
        if (current.stage != ImportStage.PASSWORD || current.backupPassword.isEmpty()) return
        _state.update { it.copy(stage = ImportStage.READING, error = null) }
        viewModelScope.launch {
            val chars = current.backupPassword.toCharArray()
            try {
                // Argon2id is deliberately slow; never on the main thread.
                val backup = withContext(Dispatchers.Default) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream")
                    input.use { vaultBackup.open(it, chars) }
                }
                val existing = withContext(Dispatchers.Default) {
                    vaultManager.withUnlocked { repository, vaultKey -> repository.importKeys(vaultKey) }
                }
                if (existing == null) {
                    backup.close()
                    return@launch
                }
                opened = backup
                val rows = ImportPlanner.plan(backup.parsed, existing) { UUID.randomUUID().toString() }
                _state.value = ImportUiState(
                    stage = ImportStage.PREVIEW,
                    source = ImportSource.BACKUP,
                    rows = rows,
                    attachmentCount = backup.attachmentCount,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                _state.update { it.copy(stage = ImportStage.PASSWORD, backupPassword = "", error = e.message) }
            } catch (_: IOException) {
                _state.update { it.copy(stage = ImportStage.PASSWORD, error = "The file could not be read.") }
            } catch (_: SecurityException) {
                _state.update { it.copy(stage = ImportStage.PASSWORD, error = "The file could not be read.") }
            } finally {
                chars.wipe()
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
        val backup = opened
        val uri = backupUri
        val attachments = if (backup != null && uri != null) {
            backup.attachments { context.contentResolver.openInputStream(uri) ?: throw IOException("No stream") }
        } else {
            null
        }
        viewModelScope.launch {
            try {
                val done = withContext(Dispatchers.Default) {
                    vaultManager.withUnlocked { repository, vaultKey -> repository.importItems(vaultKey, chosen, attachments) }
                }
                closeBackup()
                // Written or not, the plaintext preview is dropped.
                _state.value = if (done != null) {
                    ImportUiState(stage = ImportStage.DONE, source = current.source, imported = chosen.size)
                } else {
                    ImportUiState(error = "The vault locked before the import was written. Nothing was imported.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                closeBackup()
                _state.value = ImportUiState(error = "${e.message} Nothing was imported.")
            } catch (_: IOException) {
                closeBackup()
                _state.value = ImportUiState(error = "The backup could not be read again. Nothing was imported.")
            } catch (e: Exception) {
                closeBackup()
                if (!vaultManager.isUnlocked) {
                    _state.value = ImportUiState(error = "The vault locked before the import was written. Nothing was imported.")
                } else {
                    throw e
                }
            }
        }
    }

    fun reset() {
        closeBackup()
        _state.value = ImportUiState()
    }

    override fun onCleared() {
        closeBackup()
    }

    private fun closeBackup() {
        opened?.close()
        opened = null
        backupUri = null
    }

    private fun isBackup(uri: Uri): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream")
        val prefix = ByteArray(PREFIX_BYTES)
        var read = 0
        input.use {
            // Not readNBytes: that is API 33, and this app runs from API 28.
            while (read < prefix.size) {
                val n = it.read(prefix, read, prefix.size - read)
                if (n < 0) break
                read += n
            }
        }
        return vaultBackup.isBackup(prefix.copyOf(read))
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
        const val PREFIX_BYTES = 16
    }
}

enum class ImportStage { CHOOSE, READING, PASSWORD, PREVIEW, IMPORTING, DONE }

/** Where the items come from, which changes what the screens say. */
enum class ImportSource { ENPASS, BACKUP }

data class ImportUiState(
    val stage: ImportStage = ImportStage.CHOOSE,
    val source: ImportSource = ImportSource.ENPASS,
    /** The backup's own password, asked for before its contents can be read. */
    val backupPassword: String = "",
    /** Files that come with a backup's items. */
    val attachmentCount: Int = 0,
    val rows: List<ImportRow> = emptyList(),
    val warnings: ImportWarnings = ImportWarnings(),
    val imported: Int = 0,
    val error: String? = null,
) {
    val newCount: Int get() = rows.count { it.status == ImportStatus.NEW }
    val duplicateCount: Int get() = rows.count { it.status == ImportStatus.DUPLICATE }
    val selectedCount: Int get() = rows.count { it.selected }
}
