package dev.creds.vault.core.crypto

import java.nio.charset.StandardCharsets

/**
 * The vault key (VK) and the keys derived from it.
 *
 * VK is 32 random bytes generated once at setup. It is never derived from the master
 * password — the password only *seals* it. That indirection is what makes changing the
 * master password an O(1) reseal instead of re-encrypting the entire database, and it is
 * what lets biometrics and the recovery phrase be independent doors to the same vault.
 *
 * Everything else hangs off VK by HKDF-SHA256 with a distinct info string, so the
 * database passphrase, a field key, and the reuse HMAC key are cryptographically
 * unrelated: recovering one reveals nothing about the others.
 *
 * Instances are [AutoCloseable]. [close] zeroes the key material, and every derive call
 * checks it has not already been closed, so a use-after-lock is a loud failure rather
 * than silent garbage.
 */
class VaultKey(key: ByteArray) : AutoCloseable {

    private val key: ByteArray = key.copyOf()

    @Volatile
    private var closed = false

    init {
        require(key.size == KEY_BYTES) {
            "Vault key must be $KEY_BYTES bytes, got ${key.size}"
        }
    }

    /**
     * SQLCipher passphrase for the vault database.
     *
     * SQLCipher takes this as a raw key, so the database is never protected by anything
     * weaker than VK itself.
     */
    fun databasePassphrase(): ByteArray = derive(INFO_DATABASE, KEY_BYTES)

    /**
     * Per-item key for the AES-GCM layer *inside* the encrypted database.
     *
     * Binding the key to the item's uuid means a ciphertext lifted from one row cannot
     * be pasted into another and decrypt, and that a single leaked field key does not
     * unlock the rest of the vault.
     */
    fun fieldKey(itemUuid: String): ByteArray {
        require(itemUuid.isNotEmpty()) { "Item uuid must not be empty" }
        return derive(INFO_FIELD + itemUuid.toByteArray(StandardCharsets.UTF_8), AesGcm.KEY_BYTES)
    }

    /**
     * HMAC key for reuse fingerprints.
     *
     * Reuse detection is a `GROUP BY reuse_hmac` with no decryption at all. Keying the
     * HMAC off VK means those fingerprints are meaningless to anyone without the vault
     * open — they cannot be compared against a precomputed table of common passwords.
     */
    fun reuseHmacKey(): ByteArray = derive(INFO_REUSE, KEY_BYTES)

    /**
     * Copy of the raw key, for sealing under a master, biometric, or recovery key.
     *
     * The caller owns the copy and must wipe it. Nothing outside this module's sealing
     * path should ever need this.
     */
    fun exportForSealing(): ByteArray {
        check(!closed) { "Vault key has been closed" }
        return key.copyOf()
    }

    private fun derive(info: ByteArray, length: Int): ByteArray {
        check(!closed) { "Vault key has been closed" }
        return Hkdf.derive(ikm = key, salt = null, info = info, length = length)
    }

    /** Zeroes the key. Idempotent; called on every lock. */
    override fun close() {
        closed = true
        key.wipe()
    }

    companion object {
        const val KEY_BYTES: Int = 32

        // Domain separators. Changing any of these string constants makes every existing
        // vault unreadable, so they are frozen.
        private val INFO_DATABASE = "db".toByteArray(StandardCharsets.UTF_8)
        private val INFO_FIELD = "field".toByteArray(StandardCharsets.UTF_8)
        private val INFO_REUSE = "reuse".toByteArray(StandardCharsets.UTF_8)

        /** Generates a new vault key. Called exactly once per vault, at setup. */
        fun generate(): VaultKey {
            val raw = AesGcm.randomBytes(KEY_BYTES)
            return try {
                VaultKey(raw)
            } finally {
                raw.wipe()
            }
        }
    }
}
