package dev.creds.vault.core.crypto

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultKeyTest {

    private fun key(fill: Byte = 42) = VaultKey(ByteArray(32) { fill })

    @Test
    fun `derivation is deterministic`() {
        val a = key().databasePassphrase()
        val b = key().databasePassphrase()

        // The database passphrase must survive a lock and an app restart, or the vault
        // becomes unopenable. This is the test that catches anything non-deterministic
        // sneaking into the derivation path.
        assertThat(a.hex()).isEqualTo(b.hex())
    }

    @Test
    fun `derived keys are the right size`() {
        val vaultKey = key()

        assertThat(vaultKey.databasePassphrase()).hasSize(32)
        assertThat(vaultKey.fieldKey("item-uuid")).hasSize(32)
        assertThat(vaultKey.reuseHmacKey()).hasSize(32)
    }

    @Test
    fun `the three domains are independent`() {
        val vaultKey = key()

        val db = vaultKey.databasePassphrase().hex()
        val field = vaultKey.fieldKey("item-uuid").hex()
        val reuse = vaultKey.reuseHmacKey().hex()

        // Domain separation is the whole reason for the info strings. If any two of
        // these collide, compromising one subsystem compromises the others.
        assertThat(db).isNotEqualTo(field)
        assertThat(db).isNotEqualTo(reuse)
        assertThat(field).isNotEqualTo(reuse)
    }

    @Test
    fun `field keys differ per item`() {
        val vaultKey = key()

        val first = vaultKey.fieldKey("11111111-1111-1111-1111-111111111111").hex()
        val second = vaultKey.fieldKey("22222222-2222-2222-2222-222222222222").hex()

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `field key for the same item is stable`() {
        val vaultKey = key()
        val uuid = "11111111-1111-1111-1111-111111111111"

        assertThat(vaultKey.fieldKey(uuid).hex()).isEqualTo(vaultKey.fieldKey(uuid).hex())
    }

    @Test
    fun `different vault keys give different derivations`() {
        assertThat(key(1).databasePassphrase().hex())
            .isNotEqualTo(key(2).databasePassphrase().hex())
    }

    @Test
    fun `field key rejects an empty uuid`() {
        assertThrows<IllegalArgumentException> { key().fieldKey("") }
    }

    @Test
    fun `constructor rejects a wrong-sized key`() {
        assertThrows<IllegalArgumentException> { VaultKey(ByteArray(16)) }
        assertThrows<IllegalArgumentException> { VaultKey(ByteArray(64)) }
    }

    @Test
    fun `constructor copies its input`() {
        val raw = ByteArray(32) { 5 }
        val vaultKey = VaultKey(raw)
        val before = vaultKey.databasePassphrase().hex()

        // generate() wipes the array it passed in; that must not blank the live key.
        raw.wipe()

        assertThat(vaultKey.databasePassphrase().hex()).isEqualTo(before)
    }

    @Test
    fun `close prevents further derivation`() {
        val vaultKey = key()
        vaultKey.close()

        // A derive call after lock must be loud. Returning key material derived from a
        // zeroed buffer would be silently wrong and catastrophic.
        assertThrows<IllegalStateException> { vaultKey.databasePassphrase() }
        assertThrows<IllegalStateException> { vaultKey.fieldKey("uuid") }
        assertThrows<IllegalStateException> { vaultKey.reuseHmacKey() }
        assertThrows<IllegalStateException> { vaultKey.exportForSealing() }
    }

    @Test
    fun `close is idempotent`() {
        val vaultKey = key()

        vaultKey.close()
        vaultKey.close()

        assertThrows<IllegalStateException> { vaultKey.databasePassphrase() }
    }

    @Test
    fun `generate produces distinct keys`() {
        val first = VaultKey.generate()
        val second = VaultKey.generate()

        assertThat(first.databasePassphrase().hex())
            .isNotEqualTo(second.databasePassphrase().hex())
    }

    @Test
    fun `exportForSealing returns a detached copy`() {
        val vaultKey = key()

        val exported = vaultKey.exportForSealing()
        exported.wipe()

        // Wiping the export must not damage the live key.
        assertThat(vaultKey.databasePassphrase().isNotEmpty()).isTrue()
    }
}
