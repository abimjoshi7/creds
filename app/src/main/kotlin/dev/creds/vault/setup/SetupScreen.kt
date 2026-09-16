package dev.creds.vault.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.strength.StrengthBand
import dev.creds.vault.core.ui.components.QuietPanel
import dev.creds.vault.core.ui.theme.SeverityCritical
import dev.creds.vault.core.ui.theme.SeverityHigh
import dev.creds.vault.core.ui.theme.SeverityMedium
import dev.creds.vault.core.ui.theme.SeverityOk
import dev.creds.vault.ui.PasswordField

/**
 * Stable handles for the UI tests.
 *
 * Matching on a text field's label only works while the field is empty — once it holds a
 * value the label stops being the node's text — so the fields and buttons carry tags
 * instead of being addressed by what they happen to say.
 */
object SetupTags {
    const val PASSWORD = "setup:password"
    const val PASSWORD_REVEAL = "setup:password:reveal"
    const val CONFIRM = "setup:confirm"
    const val CONFIRM_REVEAL = "setup:confirm:reveal"
    const val SUBMIT = "setup:submit"
}

/**
 * Wires [SetupScreen] to its ViewModel.
 *
 * The screen itself is stateless so it can be tested without a Hilt graph — which is the
 * only practical way to verify it on a device where `FLAG_SECURE` blocks screenshots and
 * the OEM blocks uiautomator.
 */
@Composable
fun SetupRoute(
    biometricAvailable: Boolean,
    onCreated: (enableBiometric: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SetupScreen(
        state = state,
        biometricAvailable = biometricAvailable,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmChange = viewModel::onConfirmChange,
        onBiometricToggle = viewModel::onBiometricToggle,
        onSubmit = { viewModel.createVault { onCreated(state.enableBiometric) } },
        modifier = modifier,
    )
}

@Composable
fun SetupScreen(
    state: SetupUiState,
    biometricAvailable: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Creds",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Create your vault", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Local, encrypted, offline. Your master password never leaves this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Stated plainly and up front, because it is the single most important fact about
        // this app and the worst possible moment to learn it is after a lockout.
        QuietPanel {
            Text("There is no password reset", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your master password is the only way in. It is never sent anywhere " +
                    "and is not stored on this device, so nobody — including us — can " +
                    "recover your vault if you forget it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = "Master password",
            fieldTag = SetupTags.PASSWORD,
            revealTag = SetupTags.PASSWORD_REVEAL,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.password.isNotEmpty()) {
            StrengthMeter(
                band = state.strength.band,
                crackTime = state.strength.crackTimeDisplay,
                warning = state.strength.warning,
                suggestions = state.strength.suggestions,
            )
        }

        PasswordField(
            value = state.confirm,
            onValueChange = onConfirmChange,
            label = "Confirm master password",
            fieldTag = SetupTags.CONFIRM,
            revealTag = SetupTags.CONFIRM_REVEAL,
            isError = state.confirm.isNotEmpty() && state.confirm != state.password,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )

        if (biometricAvailable) {
            QuietPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Unlock with biometrics", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Adds a second way in, protected by this device's secure hardware. " +
                                "Enrolling a new fingerprint disables it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.enableBiometric, onCheckedChange = onBiometricToggle)
                }
            }
        }

        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SetupTags.SUBMIT),
        ) {
            if (state.busy) {
                // Argon2id at 64MiB takes a visible moment. Saying why stops it reading
                // as a hang.
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("Deriving key…")
            } else {
                Text("Create vault")
            }
        }
    }
}

@Composable
private fun StrengthMeter(
    band: StrengthBand,
    crackTime: String,
    warning: String,
    suggestions: List<String>,
) {
    val color = band.color()

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        LinearProgressIndicator(
            progress = { band.fraction() },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${band.label()} · takes $crackTime to crack offline",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        if (warning.isNotEmpty()) {
            Text(warning, style = MaterialTheme.typography.bodySmall)
        }
        suggestions.take(2).forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// The severity ramp is kept out of the Material scheme on purpose, so a theme change can
// never make "very weak" and "very strong" hard to tell apart.
private fun StrengthBand.color(): Color = when (this) {
    StrengthBand.VERY_WEAK -> SeverityCritical
    StrengthBand.WEAK -> SeverityHigh
    StrengthBand.FAIR -> SeverityMedium
    StrengthBand.STRONG, StrengthBand.VERY_STRONG -> SeverityOk
}

private fun StrengthBand.fraction(): Float = when (this) {
    StrengthBand.VERY_WEAK -> 0.2f
    StrengthBand.WEAK -> 0.4f
    StrengthBand.FAIR -> 0.6f
    StrengthBand.STRONG -> 0.8f
    StrengthBand.VERY_STRONG -> 1f
}

internal fun StrengthBand.label(): String = when (this) {
    StrengthBand.VERY_WEAK -> "Very weak"
    StrengthBand.WEAK -> "Weak"
    StrengthBand.FAIR -> "Fair"
    StrengthBand.STRONG -> "Strong"
    StrengthBand.VERY_STRONG -> "Very strong"
}
