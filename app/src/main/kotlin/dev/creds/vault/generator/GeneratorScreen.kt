package dev.creds.vault.generator

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.model.GeneratedValue
import dev.creds.vault.core.ui.theme.SecretTextStyle
import dev.creds.vault.items.ConfirmDialog
import dev.creds.vault.items.MASK
import dev.creds.vault.ui.SensitiveClipboard
import dev.creds.vault.ui.rememberCopySensitive
import kotlinx.coroutines.launch

@Composable
fun GeneratorRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GeneratorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copySensitive = rememberCopySensitive()

    LaunchedEffect(viewModel) { viewModel.watchHistory() }

    fun copy(label: String, value: String) {
        copySensitive(label, value)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                "Copied. Clears in ${SensitiveClipboard.CLEAR_AFTER_MS / 1000} seconds.",
                duration = SnackbarDuration.Short,
            )
        }
    }

    GeneratorScreen(
        state = state,
        actions = viewModel.actions(onCopy = {
            state.generated?.let {
                copy("Generated password", it.value)
                viewModel.onTaken()
            }
        }),
        historyActions = GeneratorHistoryActions(
            onCopy = { copy("Generated password", it.value) },
            onDelete = { viewModel.deleteHistoryEntry(it.id) },
            onClear = viewModel::clearHistory,
        ),
        onBack = onBack,
        snackbarHostState = snackbar,
        modifier = modifier,
    )
}

/**
 * The generator as a sheet over the item editor, with a button to put the value in the
 * field it was opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorSheet(
    onUse: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: GeneratorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val copySensitive = rememberCopySensitive()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GeneratorPanel(
                state = state,
                actions = viewModel.actions(onCopy = {
                    state.generated?.let {
                        copySensitive("Generated password", it.value)
                        viewModel.onTaken()
                    }
                }),
            )
            Button(
                onClick = {
                    state.generated?.let {
                        viewModel.onTaken()
                        onUse(it.value)
                    }
                },
                enabled = state.generated != null,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag(GeneratorTags.USE),
            ) {
                Text("Use this")
            }
        }
    }
}

data class GeneratorHistoryActions(
    val onCopy: (GeneratedValue) -> Unit = {},
    val onDelete: (GeneratedValue) -> Unit = {},
    val onClear: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(
    state: GeneratorUiState,
    actions: GeneratorActions,
    historyActions: GeneratorHistoryActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Generator") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "panel") { GeneratorPanel(state = state, actions = actions) }

            item(key = "history-heading") {
                Column(Modifier.padding(top = 16.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Recently used", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "The last ${VaultRepository.GENERATOR_HISTORY_SIZE} values you copied or used, " +
                                    "kept encrypted for 24 hours.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.history.isNotEmpty()) {
                            TextButton(
                                onClick = { confirmClear = true },
                                modifier = Modifier.testTag(GeneratorTags.CLEAR_HISTORY),
                            ) { Text("Clear") }
                        }
                    }
                }
            }

            if (state.history.isEmpty()) {
                item(key = "history-empty") {
                    Text(
                        "Nothing yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.history, key = { "history:${it.id}" }) { entry ->
                HistoryRow(entry, historyActions)
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear recently used?",
            text = "These values are removed from this device. Anything already saved in an item is not affected.",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClear = false
                historyActions.onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HistoryRow(entry: GeneratedValue, actions: GeneratorHistoryActions) {
    var revealed by remember(entry.id) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (revealed) entry.value else MASK,
                style = if (revealed) SecretTextStyle else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(GeneratorTags.historyValue(entry.id)),
            )
            Text(
                DateUtils.getRelativeTimeSpanString(entry.createdAt).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { revealed = !revealed }, modifier = Modifier.testTag(GeneratorTags.historyReveal(entry.id))) {
            Icon(
                if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (revealed) "Hide" else "Show",
            )
        }
        IconButton(onClick = { actions.onCopy(entry) }, modifier = Modifier.testTag(GeneratorTags.historyCopy(entry.id))) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
        }
        IconButton(onClick = { actions.onDelete(entry) }, modifier = Modifier.testTag(GeneratorTags.historyDelete(entry.id))) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove")
        }
    }
}

/** Binds the panel's actions to this ViewModel, with the caller deciding what copying does. */
fun GeneratorViewModel.actions(onCopy: () -> Unit) = GeneratorActions(
    onRegenerate = ::regenerate,
    onCopy = onCopy,
    onMode = ::setMode,
    onLength = ::setLength,
    onToggleClass = ::toggleClass,
    onExcludeAmbiguous = ::setExcludeAmbiguous,
    onRequireEach = ::setRequireEachClass,
    onWords = ::setWords,
    onSeparator = ::setSeparator,
    onCapitalization = ::setCapitalization,
    onIncludeDigit = ::setIncludeDigit,
    onPinLength = ::setPinLength,
)
