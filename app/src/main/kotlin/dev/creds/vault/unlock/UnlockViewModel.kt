package dev.creds.vault.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.crypto.BiometricKeyStore
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.prefs.VaultKeyStore
import dev.creds.vault.core.data.vault.UnlockResult
import dev.creds.vault.core.data.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Unlocking an existing vault.
 *
 * The backoff shown here is only the *display* of a rule enforced in `VaultManager`
 * against persisted counters. A UI-only lockout would be defeated by force-stopping the
 * app, so this deliberately mirrors state it does not own.
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val vaultManager: VaultManager,
    private val vaultKeyStore: VaultKeyStore,
    private val biometricKeyStore: BiometricKeyStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UnlockUiState())
    val state: StateFlow<UnlockUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(biometricOffered = vaultKeyStore.biometricSealedKey() != null)
            }
        }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun unlock(onUnlocked: () -> Unit) {
        val password = _state.value.password
        if (password.isEmpty() || _state.value.busy) return

        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                val result = withContext(Dispatchers.Default) {
                    vaultManager.unlock(chars, System.currentTimeMillis())
                }
                handle(result, onUnlocked)
            } finally {
                chars.wipe()
            }
        }
    }

    /**
     * Builds the Keystore cipher the biometric prompt must authorise.
     *
     * Returns null when the key has been invalidated — a new fingerprint enrolment
     * destroys it by design — in which case the caller falls back to the password and
     * the stale sealed copy is discarded.
     */
    suspend fun biometricCipher(): Cipher? {
        val sealed = vaultKeyStore.biometricSealedKey() ?: return null
        return try {
            biometricKeyStore.decryptCipher(sealed.iv)
        } catch (_: Exception) {
            vaultKeyStore.clearBiometricSealedKey()
            _state.update { it.copy(biometricOffered = false) }
            null
        }
    }

    fun unlockWithBiometric(cipher: Cipher, onUnlocked: () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { handle(vaultManager.unlockWithBiometric(cipher), onUnlocked) }
    }

    private suspend fun handle(result: UnlockResult, onUnlocked: () -> Unit) {
        when (result) {
            is UnlockResult.Success -> {
                _state.update { UnlockUiState(biometricOffered = it.biometricOffered) }
                onUnlocked()
            }

            is UnlockResult.WrongPassword -> {
                _state.update {
                    it.copy(busy = false, password = "", error = "Incorrect master password")
                }
                startCountdown(result.retryAfterMillis)
            }

            is UnlockResult.LockedOut -> {
                _state.update { it.copy(busy = false, error = null) }
                startCountdown(result.retryAfterMillis)
            }

            is UnlockResult.Wiped -> _state.update {
                it.copy(busy = false, wiped = true, error = "Vault wiped after too many attempts")
            }

            is UnlockResult.BiometricUnavailable -> _state.update {
                it.copy(
                    busy = false,
                    biometricOffered = false,
                    error = "Biometric unlock is no longer available",
                )
            }

            is UnlockResult.NotInitialised -> _state.update { it.copy(busy = false) }
        }
    }

    /** Ticks the remaining lockout down so the button explains itself. */
    private fun startCountdown(millis: Long) {
        if (millis <= 0) return

        viewModelScope.launch {
            var remaining = millis
            while (remaining > 0) {
                _state.update { it.copy(lockedOutMillis = remaining) }
                delay(TICK_MS)
                remaining -= TICK_MS
            }
            _state.update { it.copy(lockedOutMillis = 0) }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}

data class UnlockUiState(
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val lockedOutMillis: Long = 0,
    val biometricOffered: Boolean = false,
    val wiped: Boolean = false,
) {
    val lockedOut: Boolean get() = lockedOutMillis > 0
    val lockedOutSeconds: Int get() = ((lockedOutMillis + 999) / 1000).toInt()
    val canSubmit: Boolean get() = !busy && !lockedOut && password.isNotEmpty()
}
