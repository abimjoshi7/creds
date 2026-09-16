package dev.creds.vault.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.lock.BiometricAuthenticator
import dev.creds.vault.ui.PasswordField
import kotlinx.coroutines.launch

/** Stable handles for the UI tests. See `SetupTags` for why these exist. */
object UnlockTags {
    const val PASSWORD = "unlock:password"
    const val PASSWORD_REVEAL = "unlock:password:reveal"
    const val SUBMIT = "unlock:submit"
    const val BIOMETRIC = "unlock:biometric"
}

/**
 * Wires [UnlockScreen] to its ViewModel and to the biometric prompt.
 *
 * The screen is stateless for the same reason as setup: it has to be testable without a
 * Hilt graph or a real Keystore.
 */
@Composable
fun UnlockRoute(
    authenticator: BiometricAuthenticator?,
    onUnlocked: () -> Unit,
    onWiped: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.wiped) {
        if (state.wiped) onWiped()
    }

    UnlockScreen(
        state = state,
        biometricEnabled = state.biometricOffered && authenticator != null,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = { viewModel.unlock(onUnlocked) },
        onBiometric = {
            val prompt = authenticator ?: return@UnlockScreen
            scope.launch {
                val cipher = viewModel.biometricCipher() ?: return@launch
                val authorised = prompt.authenticate(
                    cipher = cipher,
                    title = "Unlock Creds",
                    subtitle = "Use your fingerprint to open the vault",
                ) ?: return@launch
                viewModel.unlockWithBiometric(authorised, onUnlocked)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun UnlockScreen(
    state: UnlockUiState,
    biometricEnabled: Boolean,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBiometric: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Unlock Creds", style = MaterialTheme.typography.headlineMedium)

        PasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = "Master password",
            fieldTag = UnlockTags.PASSWORD,
            revealTag = UnlockTags.PASSWORD_REVEAL,
            enabled = !state.lockedOut && !state.busy,
            isError = state.error != null,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.lockedOut) {
            // A disabled button with no explanation reads as a broken app. Saying how
            // long is left also makes the cost of guessing visible.
            Text(
                "Too many attempts. Try again in ${state.lockedOutSeconds}s.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UnlockTags.SUBMIT),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
                Text("Unlocking…")
            } else {
                Text("Unlock")
            }
        }

        if (biometricEnabled) {
            OutlinedButton(
                onClick = onBiometric,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UnlockTags.BIOMETRIC),
            ) {
                Text("Use biometrics")
            }
        }
    }
}
