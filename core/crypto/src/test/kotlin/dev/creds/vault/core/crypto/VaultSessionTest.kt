package dev.creds.vault.core.crypto

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultSessionTest {

    @Test
    fun `starts locked`() {
        val session = VaultSession()

        assertThat(session.isUnlocked).isFalse()
        assertThat(session.state.value).isInstanceOf(VaultState.Locked::class)
    }

    @Test
    fun `unlock exposes the key`() {
        val session = VaultSession()
        val vaultKey = VaultKey.generate()
        val expected = vaultKey.databasePassphrase().hex()

        session.unlock(vaultKey)

        assertThat(session.isUnlocked).isTrue()
        assertThat(session.withVaultKey { it.databasePassphrase().hex() }).isEqualTo(expected)
    }

    @Test
    fun `lock closes the key`() {
        val session = VaultSession()
        val vaultKey = VaultKey.generate()
        session.unlock(vaultKey)

        session.lock()

        assertThat(session.isUnlocked).isFalse()
        // Locking must destroy the key, not merely hide the UI.
        assertThrows<IllegalStateException> { vaultKey.databasePassphrase() }
    }

    @Test
    fun `lock is idempotent`() {
        val session = VaultSession()
        session.unlock(VaultKey.generate())

        session.lock()
        session.lock()

        assertThat(session.isUnlocked).isFalse()
    }

    @Test
    fun `locking while already locked is harmless`() {
        val session = VaultSession()

        session.lock()

        assertThat(session.isUnlocked).isFalse()
    }

    @Test
    fun `re-unlocking closes the previous key`() {
        val session = VaultSession()
        val first = VaultKey.generate()
        session.unlock(first)

        session.unlock(VaultKey.generate())

        // Otherwise the old key material is stranded with no reference left to wipe it.
        assertThrows<IllegalStateException> { first.databasePassphrase() }
        assertThat(session.isUnlocked).isTrue()
    }

    @Test
    fun `withVaultKey throws while locked`() {
        val session = VaultSession()

        assertThrows<IllegalStateException> { session.withVaultKey { it.databasePassphrase() } }
    }

    @Test
    fun `state flow reports both transitions`() {
        val session = VaultSession()

        session.unlock(VaultKey.generate())
        assertThat(session.state.value).isInstanceOf(VaultState.Unlocked::class)

        session.lock()
        assertThat(session.state.value).isInstanceOf(VaultState.Locked::class)
    }

    @Test
    fun `unlocked state does not print the key`() {
        val state = VaultState.Unlocked(VaultKey.generate())

        // A generated toString() on this is exactly what ends up in a log line.
        assertThat(state.toString()).isEqualTo("VaultState.Unlocked(vaultKey=<redacted>)")
    }
}
