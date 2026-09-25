package dev.creds.vault.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.crypto.BiometricKeyStore
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.prefs.LockPolicy
import dev.creds.vault.core.data.prefs.LockPreferences
import dev.creds.vault.core.data.vault.ChangePasswordResult
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * The lock policy, biometric unlock, and the master password.
 *
 * Every switch writes straight through to [LockPreferences]; the lock coordinator and the
 * activity already watch those, so a change applies at once without a restart.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val vaultManager: VaultManager,
    private val lockPreferences: LockPreferences,
    private val biometricKeyStore: BiometricKeyStore,
    private val strengthEstimator: PasswordStrengthEstimator,
) : ViewModel() {

    val policy: StateFlow<LockPolicy> = lockPreferences.policy
        .stateIn(viewModelScope, SharingStarted.Eagerly, LockPolicy())

    private val _passwordChange = MutableStateFlow<PasswordChangeState?>(null)

    /** The change-password dialog, or null while it is closed. */
    val passwordChange: StateFlow<PasswordChangeState?> = _passwordChange.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            // Typed passwords do not outlive the vault being open.
            vaultManager.repository.collect { if (it == null) _passwordChange.value = null }
        }
    }

    fun setLockOnBackground(value: Boolean) = write { lockPreferences.setLockOnBackground(value) }

    fun setBackgroundGraceSeconds(value: Int) = write { lockPreferences.setBackgroundGraceSeconds(value) }

    fun setLockOnScreenOff(value: Boolean) = write { lockPreferences.setLockOnScreenOff(value) }

    fun setIdleTimeoutMinutes(value: Int) = write { lockPreferences.setIdleTimeoutMinutes(value) }

    fun setWipeAfterFailures(value: Int) = write { lockPreferences.setWipeAfterFailures(value) }

    fun setSecureFlag(value: Boolean) = write { lockPreferences.setSecureFlag(value) }

    /**
     * A cipher for `BiometricPrompt` to authorise, or null when this device cannot offer
     * biometric unlock at all.
     */
    fun createBiometricCipher(): Cipher? = try {
        biometricKeyStore.createKey()
        biometricKeyStore.encryptCipher()
    } catch (_: Exception) {
        null
    }

    fun enableBiometric(cipher: Cipher) {
        viewModelScope.launch {
            try {
                vaultManager.enableBiometric(cipher)
                _message.value = "Biometric unlock is on"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _message.value = "Biometric unlock could not be turned on"
            }
        }
    }

    fun disableBiometric() {
        viewModelScope.launch {
            vaultManager.disableBiometric()
            _message.value = "Biometric unlock is off"
        }
    }

    fun biometricUnavailable() {
        _message.value = "This phone has no strong biometric set up"
    }

    fun openPasswordChange() {
        _passwordChange.value = PasswordChangeState()
    }

    fun closePasswordChange() {
        if (_passwordChange.value?.busy == true) return
        _passwordChange.value = null
    }

    fun onCurrentPasswordChange(value: String) = _passwordChange.update { it?.copy(current = value, error = null) }

    fun onNewPasswordChange(value: String) {
        val chars = value.toCharArray()
        val strength = try {
            strengthEstimator.estimate(chars)
        } finally {
            chars.wipe()
        }
        _passwordChange.update { it?.copy(new = value, strength = strength, error = null) }
    }

    fun onConfirmPasswordChange(value: String) = _passwordChange.update { it?.copy(confirm = value, error = null) }

    fun changePassword() {
        val state = _passwordChange.value ?: return
        if (state.busy) return
        val problem = validate(state)
        if (problem != null) {
            _passwordChange.update { it?.copy(error = problem) }
            return
        }
        _passwordChange.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            val current = state.current.toCharArray()
            val new = state.new.toCharArray()
            try {
                // Two Argon2id runs, one to check and one to reseal: never on the main thread.
                val result = withContext(Dispatchers.Default) { vaultManager.changeMasterPassword(current, new) }
                when (result) {
                    ChangePasswordResult.Changed -> {
                        _passwordChange.value = null
                        _message.value = "Master password changed"
                    }
                    ChangePasswordResult.WrongPassword ->
                        _passwordChange.update { it?.copy(busy = false, current = "", error = "Current password is wrong") }
                    ChangePasswordResult.Locked -> _passwordChange.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _passwordChange.update { it?.copy(busy = false, error = "The password could not be changed") }
            } finally {
                current.wipe()
                new.wipe()
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    private fun validate(state: PasswordChangeState): String? = when {
        state.current.isEmpty() -> "Enter your current password"
        state.new.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters"
        state.new != state.confirm -> "New passwords do not match"
        state.new == state.current -> "That is already your password"
        state.strength.score < MIN_SCORE -> "Too easy to guess — ${state.strength.warning.ifEmpty { "try a longer passphrase" }}"
        else -> null
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        // Same floor as setup: this is the only way into the vault.
        const val MIN_LENGTH = 10
        const val MIN_SCORE = 2
    }
}

data class PasswordChangeState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val strength: PasswordStrength = PasswordStrength.EMPTY,
    val busy: Boolean = false,
    val error: String? = null,
)
