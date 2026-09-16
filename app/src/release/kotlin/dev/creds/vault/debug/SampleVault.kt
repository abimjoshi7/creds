package dev.creds.vault.debug

import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.repository.VaultRepository

/** Release stub: sample data never ships. See the debug source set for the real one. */
object SampleVault {

    const val AVAILABLE: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun seed(repository: VaultRepository, vaultKey: VaultKey, now: Long): Int = 0
}
