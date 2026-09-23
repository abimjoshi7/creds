package dev.creds.vault.core.data.crypto

import dev.creds.vault.core.crypto.AesGcm
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.crypto.useAndWipe
import dev.creds.vault.core.model.FieldType
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The encryption layer *inside* the encrypted database, plus the derived columns that
 * make search and audit possible without decrypting anything.
 *
 * Deliberately free of `android.*`, so all of it is unit-testable on the host.
 */
@Singleton
internal class FieldCipher @Inject constructor() {

    /**
     * Seals a field value.
     *
     * The key is per-item and the AAD binds the ciphertext to both the item and the
     * field's type, so a sealed password cannot be relocated into a different item, nor
     * into a plain text field of the same item where it would render unmasked.
     */
    fun sealValue(vaultKey: VaultKey, itemUuid: String, type: FieldType, value: String): ByteArray =
        vaultKey.fieldKey(itemUuid).useAndWipe { key ->
            AesGcm.seal(key, value.toByteArray(Charsets.UTF_8), aad(itemUuid, type))
        }

    fun openValue(vaultKey: VaultKey, itemUuid: String, type: FieldType, sealed: ByteArray): String =
        vaultKey.fieldKey(itemUuid).useAndWipe { key ->
            AesGcm.open(key, sealed, aad(itemUuid, type)).decodeToString()
        }

    /** Notes get the item's key with their own AAD, so they cannot swap with a field. */
    fun sealNote(vaultKey: VaultKey, itemUuid: String, note: String): ByteArray? {
        if (note.isEmpty()) return null
        return vaultKey.fieldKey(itemUuid).useAndWipe { key ->
            AesGcm.seal(key, note.toByteArray(Charsets.UTF_8), noteAad(itemUuid))
        }
    }

    fun openNote(vaultKey: VaultKey, itemUuid: String, sealed: ByteArray?): String {
        if (sealed == null) return ""
        return vaultKey.fieldKey(itemUuid).useAndWipe { key ->
            AesGcm.open(key, sealed, noteAad(itemUuid)).decodeToString()
        }
    }

    /** Seals a generated value. Its own key and AAD, so it can never pass for a field. */
    fun sealGenerated(vaultKey: VaultKey, value: String): ByteArray =
        vaultKey.generatorHistoryKey().useAndWipe { key ->
            AesGcm.seal(key, value.toByteArray(Charsets.UTF_8), generatorAad())
        }

    fun openGenerated(vaultKey: VaultKey, sealed: ByteArray): String =
        vaultKey.generatorHistoryKey().useAndWipe { key ->
            AesGcm.open(key, sealed, generatorAad()).decodeToString()
        }

    /**
     * What goes in `search_text`.
     *
     * Null for sensitive fields — that is the single rule keeping secrets out of the FTS
     * index, and it is expressed once, here, rather than at every call site.
     */
    fun searchText(sensitive: Boolean, value: String): String? =
        if (sensitive) null else value

    /**
     * Reuse fingerprint, or null when the field is not the kind of thing reuse applies to.
     *
     * NFKC first, because `paßwort` and `paßwort` can be different codepoint sequences
     * that a user cannot tell apart; without normalisation they would look like distinct
     * passwords and reuse would go undetected.
     */
    fun reuseHmac(vaultKey: VaultKey, type: FieldType, value: String): ByteArray? {
        if (!type.isAuditable || value.isEmpty()) return null

        return vaultKey.reuseHmacKey().useAndWipe { key ->
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(key, HMAC))
            mac.doFinal(normalize(value).toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * First five uppercase hex characters of SHA-1, which is exactly the prefix HIBP's
     * k-anonymity range API takes.
     *
     * Storing only the prefix means the breach check can run against the column without
     * the full hash ever existing at rest.
     */
    fun sha1Prefix(type: FieldType, value: String): String? {
        if (!type.isAuditable || value.isEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))

        return digest.take(3)
            .joinToString("") { "%02X".format(it) }
            .substring(0, 5)
    }

    /** NFKC, exposed so the audit engine normalises identically. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)

    private fun aad(itemUuid: String, type: FieldType): ByteArray =
        "$itemUuid|${type.id}".toByteArray(Charsets.UTF_8)

    private fun generatorAad(): ByteArray = "generator".toByteArray(Charsets.UTF_8)

    private fun noteAad(itemUuid: String): ByteArray =
        "$itemUuid|note".toByteArray(Charsets.UTF_8)

    private companion object {
        const val HMAC = "HmacSHA256"
    }
}
