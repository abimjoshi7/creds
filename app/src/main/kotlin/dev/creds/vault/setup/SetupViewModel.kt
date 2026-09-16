package dev.creds.vault.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Creating the vault.
 *
 * A note on the master password's lifetime: Compose text fields hand back a [String],
 * which is immutable and cannot be erased. The crypto layer takes a [CharArray] and this
 * class converts at the boundary and wipes what it owns, but the String the UI held
 * still exists until it is collected. That is an honest limitation of the toolkit, not
 * something the conversion hides — the mitigation that actually matters is that the
 * password is never persisted anywhere.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val vaultManager: VaultManager,
    private val strengthEstimator: PasswordStrengthEstimator,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onPasswordChange(value: String) {
        val chars = value.toCharArray()
        val strength = try {
            strengthEstimator.estimate(chars)
        } finally {
            chars.wipe()
        }

        _state.update {
            it.copy(password = value, strength = strength, error = null)
        }
    }

    fun onConfirmChange(value: String) {
        _state.update { it.copy(confirm = value, error = null) }
    }

    fun onBiometricToggle(enabled: Boolean) {
        _state.update { it.copy(enableBiometric = enabled) }
    }

    /**
     * Creates the vault, then reports completion so the caller can offer biometric
     * enrolment — which needs the vault already unlocked to seal a second copy of the
     * key.
     */
    fun createVault(onCreated: () -> Unit) {
        val current = _state.value
        val problem = validate(current)
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }

        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            val chars = current.password.toCharArray()
            try {
                // Argon2id at 64MiB is deliberately slow; never on the main thread.
                withContext(Dispatchers.Default) { vaultManager.createVault(chars) }
                _state.update { it.copy(busy = false, password = "", confirm = "") }
                onCreated()
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Could not create vault") }
            } finally {
                chars.wipe()
            }
        }
    }

    private fun validate(state: SetupUiState): String? = when {
        state.password.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters"
        state.password != state.confirm -> "Passwords do not match"
        // Deliberately a floor, not a rejection of anything below "very strong": a rule
        // strict enough to be annoying gets satisfied with Password1! and a sticky note.
        state.strength.score < MIN_SCORE -> "Too easy to guess — ${state.strength.warning.ifEmpty { "try a longer passphrase" }}"
        else -> null
    }

    companion object {
        const val MIN_LENGTH = 10
        const val MIN_SCORE = 2
    }
}

data class SetupUiState(
    val password: String = "",
    val confirm: String = "",
    val strength: PasswordStrength = PasswordStrength.EMPTY,
    val enableBiometric: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = !busy && password.isNotEmpty() && confirm.isNotEmpty()
}
