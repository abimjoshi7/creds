package dev.creds.vault.core.data.crypto

import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.model.FieldType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException

/**
 * The inner encryption layer and the derived columns.
 *
 * All pure JVM — `FieldCipher` imports no `android.*`, which is what lets the rules that
 * actually protect secrets be tested on the host instead of only on a device.
 */
class FieldCipherTest {

    private val cipher = FieldCipher()
    private val vaultKey = VaultKey(ByteArray(32) { 9 })
    private val uuid = "11111111-1111-1111-1111-111111111111"

    @Test
    fun `seals and opens a value`() {
        val sealed = cipher.sealValue(vaultKey, uuid, FieldType.PASSWORD, "hunter2")

        assertThat(cipher.openValue(vaultKey, uuid, FieldType.PASSWORD, sealed)).isEqualTo("hunter2")
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val sealed = cipher.sealValue(vaultKey, uuid, FieldType.PASSWORD, "hunter2")

        assertThat(sealed.decodeToString().contains("hunter2")).isEqualTo(false)
    }

    @Test
    fun `a value cannot be moved to another item`() {
        val sealed = cipher.sealValue(vaultKey, uuid, FieldType.PASSWORD, "hunter2")
        val otherUuid = "22222222-2222-2222-2222-222222222222"

        // Different item, different key *and* different AAD. Relocating a stolen
        // ciphertext must not decrypt.
        assertThrows<GeneralSecurityException> {
            cipher.openValue(vaultKey, otherUuid, FieldType.PASSWORD, sealed)
        }
    }

    @Test
    fun `a value cannot be moved to a different field type`() {
        val sealed = cipher.sealValue(vaultKey, uuid, FieldType.PASSWORD, "hunter2")

        // Same item, so the same key — only the AAD stops this. Without it a sealed
        // password could be relabelled TEXT and would then render unmasked.
        assertThrows<GeneralSecurityException> {
            cipher.openValue(vaultKey, uuid, FieldType.TEXT, sealed)
        }
    }

    @Test
    fun `notes round trip and cannot swap with a field`() {
        val sealed = cipher.sealNote(vaultKey, uuid, "recovery codes")
        assertThat(sealed).isNotNull()

        assertThat(cipher.openNote(vaultKey, uuid, sealed)).isEqualTo("recovery codes")
        assertThrows<GeneralSecurityException> {
            cipher.openValue(vaultKey, uuid, FieldType.TEXT, sealed!!)
        }
    }

    @Test
    fun `an empty note seals to null and opens to empty`() {
        assertThat(cipher.sealNote(vaultKey, uuid, "")).isNull()
        assertThat(cipher.openNote(vaultKey, uuid, null)).isEqualTo("")
    }

    @Test
    fun `search text is null for sensitive values`() {
        // The single rule that keeps secrets out of the FTS index.
        assertThat(cipher.searchText(sensitive = true, value = "hunter2")).isNull()
        assertThat(cipher.searchText(sensitive = false, value = "alice@example.com"))
            .isEqualTo("alice@example.com")
    }

    @Test
    fun `reuse hmac is stable for the same value`() {
        val first = cipher.reuseHmac(vaultKey, FieldType.PASSWORD, "hunter2")
        val second = cipher.reuseHmac(vaultKey, FieldType.PASSWORD, "hunter2")

        assertThat(first).isNotNull()
        assertThat(first!!.contentEquals(second!!)).isTrue()
    }

    @Test
    fun `reuse hmac differs for different values`() {
        val a = cipher.reuseHmac(vaultKey, FieldType.PASSWORD, "hunter2")!!
        val b = cipher.reuseHmac(vaultKey, FieldType.PASSWORD, "hunter3")!!

        assertThat(a.contentEquals(b)).isEqualTo(false)
    }

    @Test
    fun `reuse hmac is keyed by the vault key`() {
        val other = VaultKey(ByteArray(32) { 1 })

        val a = cipher.reuseHmac(vaultKey, FieldType.PASSWORD, "hunter2")!!
        val b = cipher.reuseHmac(other, FieldType.PASSWORD, "hunter2")!!

        // Otherwise the fingerprints would be comparable against a precomputed table of
        // common passwords by anyone who got the database file.
        assertThat(a.contentEquals(b)).isEqualTo(false)
    }

    @Test
    fun `reuse hmac normalises to NFKC`() {
        // Same visible text, different codepoint sequences. Without normalisation these
        // would look like two distinct passwords and reuse would go undetected.
        val composed = "café"
        val decomposed = "café"

        val a = cipher.reuseHmac(vaultKey, FieldType.PASSWORD, composed)!!
        val b = cipher.reuseHmac(vaultKey, FieldType.PASSWORD, decomposed)!!

        assertThat(a.contentEquals(b)).isTrue()
    }

    @Test
    fun `reuse hmac is null for non-auditable types and empty values`() {
        assertThat(cipher.reuseHmac(vaultKey, FieldType.USERNAME, "alice")).isNull()
        assertThat(cipher.reuseHmac(vaultKey, FieldType.URL, "https://example.com")).isNull()
        assertThat(cipher.reuseHmac(vaultKey, FieldType.PASSWORD, "")).isNull()
    }

    @Test
    fun `sha1 prefix is five uppercase hex characters`() {
        val prefix = cipher.sha1Prefix(FieldType.PASSWORD, "password")

        assertThat(prefix).isNotNull()
        assertThat(prefix!!).hasLength(5)
        assertThat(prefix).isEqualTo("5BAA6")
    }

    @Test
    fun `sha1 prefix is null for non-auditable types and empty values`() {
        assertThat(cipher.sha1Prefix(FieldType.USERNAME, "alice")).isNull()
        assertThat(cipher.sha1Prefix(FieldType.PASSWORD, "")).isNull()
    }

    @Test
    fun `sha1 prefix differs across values`() {
        assertThat(cipher.sha1Prefix(FieldType.PASSWORD, "password"))
            .isNotEqualTo(cipher.sha1Prefix(FieldType.PASSWORD, "hunter2"))
    }

    @Test
    fun `card pin and transaction password are auditable`() {
        // A quarter of the reference vault is finance items, so these must not be
        // silently excluded from reuse and breach checks.
        assertThat(cipher.reuseHmac(vaultKey, FieldType.CARD_PIN, "1234")).isNotNull()
        assertThat(cipher.reuseHmac(vaultKey, FieldType.CARD_TXN_PASSWORD, "abcd")).isNotNull()
        assertThat(cipher.reuseHmac(vaultKey, FieldType.PIN, "0000")).isNotNull()
    }

    @Test
    fun `seals and opens attachment bytes`() {
        val bytes = "%PDF-scan".toByteArray()
        val sealed = cipher.sealAttachment(vaultKey, uuid, ATTACHMENT, bytes)

        assertThat(sealed.decodeToString().contains("%PDF-scan")).isEqualTo(false)
        assertThat(cipher.openAttachment(vaultKey, uuid, ATTACHMENT, sealed).decodeToString())
            .isEqualTo("%PDF-scan")
    }

    @Test
    fun `attachment bytes are bound to their id and their item`() {
        val sealed = cipher.sealAttachment(vaultKey, uuid, ATTACHMENT, byteArrayOf(1, 2, 3))

        // A file renamed on disk to another attachment's id must not open.
        assertThrows<GeneralSecurityException> {
            cipher.openAttachment(vaultKey, uuid, OTHER_ATTACHMENT, sealed)
        }
        assertThrows<GeneralSecurityException> {
            cipher.openAttachment(vaultKey, "22222222-2222-2222-2222-222222222222", ATTACHMENT, sealed)
        }
    }

    @Test
    fun `an attachment name cannot stand in for its bytes`() {
        val name = cipher.sealAttachmentName(vaultKey, uuid, ATTACHMENT, "passport.jpg")

        assertThat(cipher.openAttachmentName(vaultKey, uuid, ATTACHMENT, name)).isEqualTo("passport.jpg")
        assertThrows<GeneralSecurityException> {
            cipher.openAttachment(vaultKey, uuid, ATTACHMENT, name)
        }
    }

    private companion object {
        const val ATTACHMENT = "aaaaaaaa-0000-4000-8000-000000000001"
        const val OTHER_ATTACHMENT = "bbbbbbbb-0000-4000-8000-000000000002"
    }
}
