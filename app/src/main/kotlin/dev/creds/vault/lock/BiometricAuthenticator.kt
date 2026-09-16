package dev.creds.vault.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Wraps `BiometricPrompt` as a suspending call.
 *
 * Always authenticates a [Cipher] inside a `CryptoObject`. That matters: prompting for a
 * fingerprint and then, on a callback, deciding to proceed is a check a patched process
 * can simply skip. Binding the Keystore cipher to the prompt means the *key* stays
 * unusable until the hardware says the user authenticated.
 */
class BiometricAuthenticator(private val activity: FragmentActivity) {

    /**
     * Shows the prompt.
     *
     * @return the authorised cipher, or null if the user cancelled or authentication
     *   failed. Cancellation is an ordinary outcome, not an error — the master password
     *   is always still available behind it.
     */
    suspend fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String,
    ): Cipher? = suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (continuation.isActive) continuation.resume(null)
                }

                // Deliberately not resuming on onAuthenticationFailed: that fires on a
                // single unrecognised finger, and the prompt stays up for another try.
                // Resuming here would dismiss the flow on the first smudged read.
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // Class 3 only. A weak biometric cannot gate a Keystore key bound to
            // setUserAuthenticationRequired, and we would rather offer nothing than
            // offer something that looks like protection and is not.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use master password")
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))

        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    companion object {

        /** Whether this device can do class-3 biometrics right now. */
        fun isAvailable(activity: FragmentActivity): Boolean =
            BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }
}
