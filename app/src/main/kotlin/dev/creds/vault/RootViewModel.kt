package dev.creds.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.crypto.BiometricKeyStore
import dev.creds.vault.core.crypto.VaultState
import dev.creds.vault.core.data.prefs.LockPolicy
import dev.creds.vault.core.data.prefs.LockPreferences
import dev.creds.vault.core.data.vault.VaultManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Decides which of the three top-level destinations the app is in.
 *
 * Derived from the session rather than from navigation history: a lock can happen at any
 * moment, from a timer or the screen switching off, and the UI has to follow that
 * immediately rather than on the next tap.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val vaultManager: VaultManager,
    private val biometricKeyStore: BiometricKeyStore,
    lockPreferences: LockPreferences,
) : ViewModel() {

    private val initialised = MutableStateFlow<Boolean?>(null)

    val policy: StateFlow<LockPolicy> = lockPreferences.policy
        .stateIn(viewModelScope, SharingStarted.Eagerly, LockPolicy())

    val state: StateFlow<RootState> =
        combine(initialised, vaultManager.state) { isInitialised, vaultState ->
            when {
                isInitialised == null -> RootState.Loading
                !isInitialised -> RootState.Setup
                vaultState is VaultState.Unlocked -> RootState.Unlocked
                else -> RootState.Locked
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    init {
        refresh()
    }

    /** Re-reads whether a vault exists. Called after setup completes or after a wipe. */
    fun refresh() {
        viewModelScope.launch { initialised.value = vaultManager.isInitialised() }
    }

    fun lock() = vaultManager.lock()

    /**
     * Creates the Keystore key and returns a cipher for `BiometricPrompt` to authorise.
     *
     * Returns null when the device refuses to create the key at all — no enrolled
     * biometric, or hardware that cannot honour the required key properties. The caller
     * treats that as "biometrics are not on offer" rather than as a failure, because the
     * master password already works.
     */
    fun createBiometricCipher(): Cipher? = try {
        biometricKeyStore.createKey()
        biometricKeyStore.encryptCipher()
    } catch (_: Exception) {
        null
    }

    /** Seals a second copy of the vault key under an authorised biometric cipher. */
    fun enableBiometric(cipher: Cipher) {
        viewModelScope.launch { vaultManager.enableBiometric(cipher) }
    }
}

sealed interface RootState {

    /** Deciding. Rendered as a blank secure screen, never as the vault. */
    data object Loading : RootState

    data object Setup : RootState

    data object Locked : RootState

    data object Unlocked : RootState
}
