package dev.creds.vault.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.ui.components.QuietPanel
import dev.creds.vault.ui.PasswordField
import dev.creds.vault.ui.StrengthMeter

object ExportTags {
    const val PASSWORD = "export:password"
    const val PASSWORD_REVEAL = "export:password:reveal"
    const val CONFIRM = "export:confirm"
    const val CONFIRM_REVEAL = "export:confirm:reveal"
    const val EXPORT = "export:go"
    const val ERROR = "export:error"
    const val DONE = "export:done"
}

/** Everything the export screen can ask for; defaults are no-ops for tests. */
data class ExportActions(
    val onBack: () -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onConfirmChange: (String) -> Unit = {},
    val onExport: () -> Unit = {},
)

@Composable
fun ExportRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Written straight to wherever the user chooses; nothing is staged in app storage.
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::exportTo)
    }
    ExportScreen(
        state = state,
        actions = ExportActions(
            onBack = onBack,
            onPasswordChange = viewModel::onPasswordChange,
            onConfirmChange = viewModel::onConfirmChange,
            onExport = { if (viewModel.readyToExport()) saveAs.launch(viewModel.suggestedFileName()) },
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(state: ExportUiState, actions: ExportActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Export backup") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.done) {
                Column(Modifier.testTag(ExportTags.DONE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.exported == 1) "Backed up 1 item." else "Backed up ${state.exported} items.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Keep the file somewhere other than this phone. It opens only with the password you just chose.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = actions.onBack, shape = MaterialTheme.shapes.medium) { Text("Done") }
                }
                return@Column
            }

            Text(
                "Saves your whole vault — every item, its history, tags, trusted apps and sites, and files — " +
                    "as one encrypted .vault file you can import on another phone or after reinstalling.",
                style = MaterialTheme.typography.bodyLarge,
            )
            QuietPanel {
                Text("The file has its own password", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose it now. Nobody can open the backup without it — including us — so write it down " +
                        "somewhere safe. It does not need to be your master password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PasswordField(
                value = state.password,
                onValueChange = actions.onPasswordChange,
                label = "Backup password",
                fieldTag = ExportTags.PASSWORD,
                revealTag = ExportTags.PASSWORD_REVEAL,
                imeAction = ImeAction.Next,
                enabled = !state.busy,
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
                onValueChange = actions.onConfirmChange,
                label = "Confirm backup password",
                fieldTag = ExportTags.CONFIRM,
                revealTag = ExportTags.CONFIRM_REVEAL,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ExportTags.ERROR))
            }

            Button(
                onClick = actions.onExport,
                enabled = !state.busy && state.password.isNotEmpty() && state.confirm.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ExportTags.EXPORT),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text("Encrypting…")
                } else {
                    Text("Choose where to save")
                }
            }
        }
    }
}
