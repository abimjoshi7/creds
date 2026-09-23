package dev.creds.vault.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.creds.vault.core.domain.totp.Totp
import dev.creds.vault.core.ui.theme.CredsRadiusMedium
import dev.creds.vault.core.ui.theme.SecretTextStyle
import dev.creds.vault.ui.rememberCopySensitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.shape.RoundedCornerShape

object TotpTags {
    const val CODE = "totp:code"
    const val COPY = "totp:copy"
}

/**
 * Live TOTP readout for a secret field. Hidden while the secret is blank or unparseable
 * so a half-typed Base32 string does not flash errors.
 */
@Composable
fun TotpCodePanel(
    secret: String,
    modifier: Modifier = Modifier,
    onCopy: ((label: String, value: String) -> Unit)? = null,
) {
    val spec = remember(secret) { Totp.parse(secret) } ?: return
    var code by remember(spec) { mutableStateOf(Totp.generate(spec)) }
    val defaultCopy = rememberCopySensitive()
    val copy = onCopy ?: defaultCopy

    LaunchedEffect(spec) {
        while (isActive) {
            code = Totp.generate(spec)
            delay(250)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CredsRadiusMedium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "One-time code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTotp(code.value),
                    style = SecretTextStyle.copy(fontSize = 28.sp, letterSpacing = 2.sp),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.testTag(TotpTags.CODE),
                )
                TextButton(
                    onClick = { copy("One-time code", code.value) },
                    modifier = Modifier.testTag(TotpTags.COPY),
                ) {
                    Text("Copy")
                }
            }
            LinearProgressIndicator(
                progress = { code.remainingSeconds.toFloat() / code.periodSeconds.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = if (code.remainingSeconds <= 5) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                "${code.remainingSeconds}s remaining",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTotp(code: String): String =
    if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}"
    else if (code.length == 8) "${code.substring(0, 4)} ${code.substring(4)}"
    else code
