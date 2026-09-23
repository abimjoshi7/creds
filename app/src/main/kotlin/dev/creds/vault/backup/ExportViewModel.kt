package dev.creds.vault.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.backup.BackupException
import dev.creds.vault.core.data.backup.VaultBackup
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Encrypted export: choose a password for the file, choose where to save it, write it.
 *
 * The password follows the master password's rules, since the file is a full copy of the
 * vault. It is held as a [String] while typed — the same toolkit limitation `SetupViewModel`
 * describes — and dropped when the export finishes or the vault locks.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val vaultManager: VaultManager,
    private val vaultBackup: VaultBackup,
    private val strengthEstimator: PasswordStrengthEstimator,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            vaultManager.repository.collect { if (it == null) _state.value = ExportUiState() }
        }
    }

    fun onPasswordChange(value: String) {
        val chars = value.toCharArray()
        val strength = try {
            strengthEstimator.estimate(chars)
        } finally {
            chars.wipe()
        }
        _state.update { it.copy(password = value, strength = strength, error = null) }
    }

    fun onConfirmChange(value: String) = _state.update { it.copy(confirm = value, error = null) }

    /** Checks the password; true when the screen may ask where to save. */
    fun readyToExport(): Boolean {
        val problem = validate(_state.value)
        _state.update { it.copy(error = problem) }
        return problem == null
    }

    fun suggestedFileName(): String =
        "vaultesque-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.vault"

    fun exportTo(uri: Uri) {
        val current = _state.value
        if (current.busy || validate(current) != null) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val chars = current.password.toCharArray()
            try {
                val count = withContext(Dispatchers.IO) {
                    vaultManager.withUnlocked { repository, vaultKey ->
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No stream")
                        output.use { vaultBackup.export(repository, vaultKey, chars, it, System.currentTimeMillis()) }
                    }
                }
                _state.value = if (count != null) {
                    ExportUiState(done = true, exported = count)
                } else {
                    discard(uri)
                    ExportUiState(error = "The vault locked before the backup was written.")
                }
            } catch (e: CancellationException) {
                discard(uri)
                throw e
            } catch (e: BackupException) {
                discard(uri)
                _state.update { it.copy(busy = false, error = e.message) }
            } catch (_: Exception) {
                discard(uri)
                _state.update {
                    it.copy(busy = false, error = if (vaultManager.isUnlocked) "The backup could not be written." else "The vault locked before the backup was written.")
                }
            } finally {
                chars.wipe()
            }
        }
    }

    /** A half-written backup is useless and misleading; remove it rather than leave it. */
    private suspend fun discard(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (_: Exception) {
                // The provider may not support deletion; the incomplete file fails to open anyway.
            }
        }
    }

    private fun validate(state: ExportUiState): String? = when {
        state.password.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters"
        state.password != state.confirm -> "Passwords do not match"
        state.strength.score < MIN_SCORE -> "Too easy to guess — ${state.strength.warning.ifEmpty { "try a longer passphrase" }}"
        else -> null
    }

    companion object {
        // The master password's floor: this file is the whole vault.
        const val MIN_LENGTH = 10
        const val MIN_SCORE = 2
    }
}

data class ExportUiState(
    val password: String = "",
    val confirm: String = "",
    val strength: PasswordStrength = PasswordStrength.EMPTY,
    val busy: Boolean = false,
    val done: Boolean = false,
    val exported: Int = 0,
    val error: String? = null,
)
