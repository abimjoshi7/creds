package dev.creds.vault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.creds.vault.core.ui.theme.CredsTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Blocks screenshots, screen recording, and the recents-list thumbnail.
        // Applied before setContent so no frame is ever capturable.
        // Checkpoint 4 makes this read the user's preference; the default stays on.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()
        setContent {
            CredsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    ScaffoldPlaceholder(Modifier.padding(insets))
                }
            }
        }
    }
}

@Composable
private fun ScaffoldPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Creds", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Checkpoint 1 — module scaffold. Crypto lands next.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
