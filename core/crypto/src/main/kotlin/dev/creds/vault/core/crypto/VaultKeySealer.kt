package dev.creds.vault.core.crypto

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seals and opens the vault key under a master password.
 *
 * This is door [1] of the three in the spec's key hierarchy, and the only mandatory one.
 * Doors [2] (Keystore biometric) and [3] (recovery phrase) seal the *same* VK
 * independently, which is why changing the master password never touches the database.
 *
 * The sealed form carries the KDF parameters it was created with so a vault created on
 * old defaults still opens after we raise them.
 */
@Singleton
class VaultKeySealer @Inject constructor(
    private val passwordHasher: PasswordHasher,
) {

    /**
     * Seals [vaultKey] under [password], generating a fresh salt.
     *
     * [password] is left intact for the caller to wipe — setup seals the same VK under
     * more than one door and needs the password again for the recovery kit.
     */
    fun seal(
        vaultKey: VaultKey,
        password: CharArray,
        params: KdfParams = KdfParams.DEFAULT,
    ): SealedVaultKey {
        val salt = AesGcm.randomBytes(KdfParams.SALT_BYTES)
        val masterKey = passwordHasher.deriveMasterKey(password, salt, params)

        return masterKey.useAndWipe { mk ->
            val raw = vaultKey.exportForSealing()
            raw.useAndWipe { plaintext ->
                SealedVaultKey(
                    ciphertext = AesGcm.seal(mk, plaintext),
                    salt = salt,
                    params = params,
                )
            }
        }
    }

    /**
     * Re-seals an already-open vault key under a new password.
     *
     * The master password change path: VK stays the same, so no data is rewritten.
     */
    fun reseal(
        vaultKey: VaultKey,
        newPassword: CharArray,
        params: KdfParams = KdfParams.DEFAULT,
    ): SealedVaultKey = seal(vaultKey, newPassword, params)

    /**
     * Opens [sealed] with [password].
     *
     * @return the vault key, or null when the password is wrong. Null rather than an
     *   exception because a wrong password is the expected path, not an error — the
     *   unlock screen's backoff handles it. A malformed or truncated blob still throws,
     *   since that means corruption, not a typo.
     */
    fun open(sealed: SealedVaultKey, password: CharArray): VaultKey? {
        val masterKey = passwordHasher.deriveMasterKey(password, sealed.salt, sealed.params)

        return masterKey.useAndWipe { mk ->
            val raw = try {
                AesGcm.open(mk, sealed.ciphertext)
            } catch (_: javax.crypto.AEADBadTagException) {
                // Tag mismatch: the derived master key is wrong, i.e. the password is.
                // Caught narrowly on purpose — a malformed or truncated blob means
                // corruption, not a typo, and must stay an exception so it surfaces
                // instead of being reported to the user as "wrong password" forever.
                return@useAndWipe null
            }

            raw.useAndWipe { VaultKey(it) }
        }
    }
}

/**
 * A vault key sealed under one door, as persisted.
 *
 * [salt] and [params] are not secret — they are needed to re-derive the master key and
 * are useless without the password.
 */
data class SealedVaultKey(
    /** Output of [AesGcm.seal]: version, nonce, ciphertext and tag. */
    val ciphertext: ByteArray,
    val salt: ByteArray,
    val params: KdfParams,
) {
    // Data class equals/hashCode compare ByteArray by identity, which is never what a
    // caller means here. Overridden so round-trip tests and cache lookups behave.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealedVaultKey) return false
        return ciphertext.contentEquals(other.ciphertext) &&
            salt.contentEquals(other.salt) &&
            params == other.params
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + params.hashCode()
        return result
    }
}
