package dev.creds.vault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.creds.vault.core.ui.theme.CredsTheme
import dev.creds.vault.lock.BiometricAuthenticator
import dev.creds.vault.lock.LockCoordinator
import dev.creds.vault.setup.SetupRoute
import dev.creds.vault.unlock.UnlockRoute
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single activity.
 *
 * Extends [FragmentActivity] rather than `ComponentActivity` because `BiometricPrompt`
 * accepts nothing else — it needs a fragment manager to host its dialog.
 * `FragmentActivity` is itself a `ComponentActivity`, so Compose and edge-to-edge are
 * unaffected.
 *
 * Top-level routing is driven by vault state rather than by a navigation graph: a lock
 * can fire from a timer or the screen switching off at any moment, and the correct
 * response is to replace what is on screen immediately, not to schedule a navigation
 * that a back press could undo.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var lockCoordinator: LockCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Applied before setContent so no frame is ever capturable. The user's preference
        // is read below and may relax it, but the default holds until then — the safe
        // direction is to start secure and loosen, never the reverse.
        setSecure(true)

        enableEdgeToEdge()
        setContent {
            CredsTheme {
                val root: RootViewModel = hiltViewModel()
                val state by root.state.collectAsStateWithLifecycle()
                val policy by root.policy.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()

                LaunchedEffect(policy.secureFlag) { setSecure(policy.secureFlag) }

                // Hoisted above the lock switch: see VaultNavHost.
                val navController = rememberNavController()

                val authenticator = remember { BiometricAuthenticator(this@MainActivity) }
                val biometricAvailable = remember {
                    BiometricAuthenticator.isAvailable(this@MainActivity)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    val content = Modifier.padding(insets)

                    when (state) {
                        // Renders nothing rather than guessing. Showing the unlock screen
                        // and then flipping to setup would be a worse first impression
                        // than a blank moment.
                        RootState.Loading -> Box(content.fillMaxSize())

                        RootState.Setup -> SetupRoute(
                            biometricAvailable = biometricAvailable,
                            onCreated = { enableBiometric ->
                                root.refresh()
                                if (enableBiometric) {
                                    scope.launch { enrolBiometric(root, authenticator) }
                                }
                            },
                            modifier = content,
                        )

                        RootState.Locked -> UnlockRoute(
                            authenticator = authenticator,
                            // Nothing to do: unlocking flips the session state, which the
                            // when() above is already collecting.
                            onUnlocked = {},
                            onWiped = root::refresh,
                            modifier = content,
                        )

                        // Not padded: the vault screens draw their own top bars, which
                        // apply the status bar inset themselves.
                        RootState.Unlocked -> VaultNavHost(
                            navController = navController,
                            onLock = root::lock,
                            modifier = Modifier.consumeWindowInsets(insets),
                        )
                    }
                }
            }
        }
    }

    /** Resets the idle timer. Any touch anywhere counts. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        lockCoordinator.noteInteraction()
    }

    /**
     * Enrols biometric unlock immediately after setup, while the vault is still open.
     *
     * Every failure here is silent and non-fatal by design: the vault already exists and
     * the master password already opens it, so a declined prompt or unusable hardware
     * should leave the user in a working vault, not in an error state.
     */
    private suspend fun enrolBiometric(root: RootViewModel, authenticator: BiometricAuthenticator) {
        val cipher = root.createBiometricCipher() ?: return
        val authorised = authenticator.authenticate(
            cipher = cipher,
            title = "Enable biometric unlock",
            subtitle = "Confirm to seal a second copy of your vault key",
        ) ?: return
        root.enableBiometric(authorised)
    }

    private fun setSecure(secure: Boolean) {
        if (secure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
