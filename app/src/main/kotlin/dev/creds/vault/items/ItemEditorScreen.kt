package dev.creds.vault.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.FieldHistoryEntry
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.ui.theme.SecretTextStyle
import java.text.DateFormat
import java.util.Date

object ItemEditorTags {
    const val TITLE = "item:title"
    const val NOTE = "item:note"
    const val SAVE = "item:save"
    const val MISSING = "item:missing"
    const val HISTORY = "item:history"
    fun field(key: String) = "item:field:$key"
    fun history(key: String) = "item:history:$key"
}

@Composable
fun ItemEditorRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ItemEditorScreen(
        state = state,
        onBack = onBack,
        onTitleChange = viewModel::onTitleChange,
        onNoteChange = viewModel::onNoteChange,
        onFieldChange = viewModel::onFieldChange,
        onToggleFavorite = viewModel::toggleFavorite,
        onOpenHistory = viewModel::openHistory,
        onDismissHistory = viewModel::dismissHistory,
        onSave = { viewModel.save(onSaved) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorScreen(
    state: ItemEditorUiState,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onFieldChange: (String, String) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenHistory: (String) -> Unit = {},
    onDismissHistory: () -> Unit = {},
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Text(
                        when {
                            state.missing -> "Item"
                            state.isNew -> "New ${TemplateCatalog.displayName(state.template).lowercase()}"
                            else -> TemplateCatalog.displayName(state.template)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.missing && !state.loading) {
                        IconToggleButton(
                            checked = state.favorite,
                            onCheckedChange = { onToggleFavorite() },
                        ) {
                            Icon(
                                if (state.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (state.favorite) "Remove from favorites" else "Add to favorites",
                                tint = if (state.favorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(
                            onClick = onSave,
                            enabled = state.canSave,
                            modifier = Modifier.testTag(ItemEditorTags.SAVE),
                        ) {
                            Text(if (state.busy) "Saving…" else "Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }

            state.missing -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(32.dp)
                    .testTag(ItemEditorTags.MISSING),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This item is gone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "It may have been deleted while the vault was locked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onBack, shape = MaterialTheme.shapes.medium) { Text("Back") }
            }

            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    label = { Text("Title") },
                    singleLine = true,
                    isError = state.error != null && state.title.isBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ItemEditorTags.TITLE),
                )

                state.fields.forEach { field ->
                    ItemFieldEditor(
                        field = field,
                        onValueChange = { onFieldChange(field.key, it) },
                        onOpenHistory = { onOpenHistory(field.key) },
                    )
                    if (field.type == FieldType.TOTP && field.value.isNotBlank()) {
                        TotpCodePanel(secret = field.value)
                    }
                }

                OutlinedTextField(
                    value = state.note,
                    onValueChange = onNoteChange,
                    label = { Text("Notes") },
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ItemEditorTags.NOTE),
                )

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${ItemEditorTags.SAVE}:bottom"),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("Saving…")
                    } else {
                        Text(if (state.isNew) "Create item" else "Save changes")
                    }
                }
            }
        }
    }

    state.historyField?.let { field ->
        FieldHistoryDialog(
            fieldLabel = field.label,
            loading = state.historyLoading,
            entries = state.history,
            onDismiss = onDismissHistory,
        )
    }
}

@Composable
private fun ItemFieldEditor(
    field: EditableField,
    onValueChange: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val secretLike = field.sensitive || field.type == FieldType.PASSWORD ||
        field.type == FieldType.PIN || field.type == FieldType.CARD_PIN ||
        field.type == FieldType.CARD_CVC || field.type == FieldType.CARD_TXN_PASSWORD ||
        field.type == FieldType.TOTP || field.type == FieldType.CARD_NUMBER

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (secretLike) {
            var revealed by remember(field.key) { mutableStateOf(false) }
            OutlinedTextField(
                value = field.value,
                onValueChange = onValueChange,
                label = { Text(field.label) },
                singleLine = field.type != FieldType.MULTILINE,
                shape = MaterialTheme.shapes.medium,
                textStyle = if (revealed) SecretTextStyle else MaterialTheme.typography.bodyLarge,
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.type) {
                        FieldType.PIN, FieldType.CARD_PIN, FieldType.CARD_CVC, FieldType.NUMERIC ->
                            KeyboardType.NumberPassword
                        else -> KeyboardType.Password
                    },
                    imeAction = ImeAction.Next,
                ),
                trailingIcon = {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(if (revealed) "Hide" else "Show")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ItemEditorTags.field(field.key)),
            )
        } else {
            OutlinedTextField(
                value = field.value,
                onValueChange = onValueChange,
                label = { Text(field.label) },
                singleLine = field.type != FieldType.MULTILINE,
                minLines = if (field.type == FieldType.MULTILINE) 3 else 1,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.type) {
                        FieldType.EMAIL -> KeyboardType.Email
                        FieldType.URL -> KeyboardType.Uri
                        FieldType.PHONE -> KeyboardType.Phone
                        FieldType.NUMERIC -> KeyboardType.Number
                        else -> KeyboardType.Text
                    },
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ItemEditorTags.field(field.key)),
            )
        }

        if (field.sensitive && field.historyCount > 0) {
            TextButton(
                onClick = onOpenHistory,
                modifier = Modifier.testTag(ItemEditorTags.history(field.key)),
            ) {
                Text("History (${field.historyCount})")
            }
        }
    }
}

@Composable
private fun FieldHistoryDialog(
    fieldLabel: String,
    loading: Boolean,
    entries: List<FieldHistoryEntry>,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    var revealedId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("Previous $fieldLabel") },
        text = {
            when {
                loading -> CircularProgressIndicator()
                entries.isEmpty() -> Text(
                    "No previous values.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Older values are kept encrypted. Reveal only what you need.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    entries.forEach { entry ->
                        val shown = revealedId == entry.id
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                dateFormat.format(Date(entry.replacedAt)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (shown) entry.value else "••••••••",
                                    style = if (shown) SecretTextStyle else MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    revealedId = if (shown) null else entry.id
                                }) {
                                    Text(if (shown) "Hide" else "Show")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ItemEditorTags.HISTORY)) {
                Text("Close")
            }
        },
    )
}
