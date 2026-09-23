package dev.creds.vault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.creds.vault.core.domain.strength.StrengthBand
import dev.creds.vault.core.ui.theme.SeverityCritical
import dev.creds.vault.core.ui.theme.SeverityHigh
import dev.creds.vault.core.ui.theme.SeverityMedium
import dev.creds.vault.core.ui.theme.SeverityOk

/**
 * A zxcvbn score as a bar, a label and the offline crack time, with the estimator's
 * warning and top suggestions when it has any.
 */
@Composable
fun StrengthMeter(
    band: StrengthBand,
    crackTime: String,
    warning: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
) {
    val color = band.color()

    Column(
        modifier = modifier,
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
