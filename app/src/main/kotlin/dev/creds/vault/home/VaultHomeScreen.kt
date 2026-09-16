package dev.creds.vault.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder for the vault list, which is checkpoint 5.
 *
 * It exists now so checkpoint 4 is genuinely installable and testable end to end: create
 * a vault, land here, lock it, unlock it again.
 */
@Composable
fun VaultHomeScreen(
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vault unlocked", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Checkpoint 4 — setup, unlock and lock policy. The item list arrives in " +
                "checkpoint 5.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
            Text("Lock vault")
        }
    }
}
