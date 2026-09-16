package dev.creds.vault.core.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the vault key while the vault is unlocked, and zeroes it on lock.
 *
 * Single source of truth for "is the vault open". The lock policy — backgrounding,
 * screen off, idle timeout — lives above this in `:app`; this class only owns the key's
 * lifetime and makes sure locking actually destroys the key rather than merely hiding
 * the UI.
 *
 * [state] is what the navigation graph observes: a lock must kick the user out of an
 * item screen immediately, not on next interaction.
 */
@Singleton
class VaultSession @Inject constructor() {

    private val _state = MutableStateFlow<VaultState>(VaultState.Locked)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    val isUnlocked: Boolean get() = _state.value is VaultState.Unlocked

    /**
     * Takes ownership of [vaultKey] and marks the vault unlocked.
     *
     * Unlocking while already unlocked closes the previous key first, so a re-unlock
     * cannot strand key material with no reference left to wipe it.
     */
    fun unlock(vaultKey: VaultKey) {
        (_state.value as? VaultState.Unlocked)?.vaultKey?.close()
        _state.value = VaultState.Unlocked(vaultKey)
    }

    /**
     * Zeroes the vault key and marks the vault locked. Idempotent.
     *
     * After this, any derive call on the old key throws rather than returning stale
     * material.
     */
    fun lock() {
        val current = _state.value
        _state.value = VaultState.Locked
        (current as? VaultState.Unlocked)?.vaultKey?.close()
    }

    /**
     * Runs [block] with the vault key.
     *
     * @throws IllegalStateException when locked. Callers in the data layer should never
     *   be reachable while locked; if one is, that is a navigation bug and should be
     *   loud.
     */
    fun <R> withVaultKey(block: (VaultKey) -> R): R {
        val current = _state.value
        check(current is VaultState.Unlocked) { "Vault is locked" }
        return block(current.vaultKey)
    }
}

sealed interface VaultState {

    /** No key in memory. The only state that survives a lock. */
    data object Locked : VaultState

    /**
     * Unlocked, holding live key material.
     *
     * Deliberately not a `data class`: a generated `toString()` on something holding the
     * vault key is exactly the kind of thing that ends up in a log line.
     */
    class Unlocked(val vaultKey: VaultKey) : VaultState {
        override fun toString(): String = "VaultState.Unlocked(vaultKey=<redacted>)"
    }
}
