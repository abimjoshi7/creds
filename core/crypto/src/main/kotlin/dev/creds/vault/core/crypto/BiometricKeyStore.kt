package dev.creds.vault.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Door [2]: a hardware-backed AES-256-GCM key that only unlocks after biometric auth.
 *
 * The key never leaves the TEE or secure element. We hand a [Cipher] to `BiometricPrompt`
 * inside a `CryptoObject`; the framework authorises the *operation*, not just the UI, so
 * a rooted process cannot skip the prompt and use the key anyway.
 *
 * Key policy, all from the spec:
 * - `setUserAuthenticationRequired(true)` — no auth, no key.
 * - `setInvalidatedByBiometricEnrollment(true)` — enrolling a new fingerprint destroys
 *   the key. Someone who coerces a device unlock and adds their own finger gets a
 *   permanently invalidated key and a vault that falls back to the master password.
 * - `setUnlockedDeviceRequired(true)` — unusable while the screen is locked.
 * - StrongBox when the device has it, with a graceful fallback to the TEE. The POCO F1
 *   has no StrongBox, so the fallback is the live path there, not a theoretical one.
 *
 * Because no validity duration is set, authentication authorises exactly one crypto
 * operation. That is the strict setting and the one worth paying for.
 */
@Singleton
class BiometricKeyStore @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /** True when a biometric-sealed copy of the vault key could exist. */
    fun hasKey(alias: String = DEFAULT_ALIAS): Boolean = keyStore.containsAlias(alias)

    /**
     * Creates the biometric key, replacing any existing one.
     *
     * @return true when the key is StrongBox-backed, false when it fell back to the TEE.
     *   Worth surfacing in settings — it is the difference between a discrete security
     *   chip and the main SoC's trusted world.
     */
    fun createKey(alias: String = DEFAULT_ALIAS): Boolean {
        deleteKey(alias)
        return try {
            generate(alias, strongBox = true)
            true
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, strongBox = false)
            false
        }
    }

    /**
     * Cipher for sealing the vault key. Pass it to `BiometricPrompt` in a `CryptoObject`,
     * then call [sealWith] on the authorised cipher the callback hands back.
     *
     * The IV is generated inside the Keystore; do not supply one.
     */
    fun encryptCipher(alias: String = DEFAULT_ALIAS): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(alias))
        }

    /**
     * Cipher for opening a previously sealed vault key.
     *
     * @throws KeyPermanentlyInvalidatedException when biometric enrolment changed. The
     *   caller must treat this as "biometric unlock is gone", drop the sealed copy, and
     *   ask for the master password — never as a generic error.
     */
    fun decryptCipher(iv: ByteArray, alias: String = DEFAULT_ALIAS): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(alias), GCMParameterSpec(AesGcm.TAG_BITS, iv))
        }

    /**
     * Seals [vaultKey] with a cipher already authorised by `BiometricPrompt`.
     *
     * Returns the ciphertext and the Keystore-chosen IV, both of which must be persisted.
     */
    fun sealWith(cipher: Cipher, vaultKey: VaultKey): BiometricSealedKey {
        val raw = vaultKey.exportForSealing()
        return raw.useAndWipe {
            BiometricSealedKey(ciphertext = cipher.doFinal(it), iv = cipher.iv)
        }
    }

    /** Opens a sealed vault key with a cipher already authorised by `BiometricPrompt`. */
    fun openWith(cipher: Cipher, sealed: BiometricSealedKey): VaultKey {
        val raw = cipher.doFinal(sealed.ciphertext)
        return raw.useAndWipe { VaultKey(it) }
    }

    /** Removes the key. Called when the user turns biometric unlock off. */
    fun deleteKey(alias: String = DEFAULT_ALIAS) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun secretKey(alias: String): SecretKey =
        keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("No biometric key under alias '$alias'")

    private fun generate(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AesGcm.KEY_BYTES * 8)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val DEFAULT_ALIAS: String = "creds.vault.biometric.v1"
    }
}

/** The vault key sealed under the Keystore biometric key, as persisted. */
data class BiometricSealedKey(
    val ciphertext: ByteArray,
    /** Chosen by the Keystore, not by us. Required to build the decrypt cipher. */
    val iv: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BiometricSealedKey) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
}
