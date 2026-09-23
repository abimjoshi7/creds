package dev.creds.vault.items

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.ui.theme.SecretTextStyle

object ItemEditorTags {
    const val TITLE = "item:title"
    const val NOTE = "item:note"
    const val SAVE = "item:save"
    const val MISSING = "item:missing"
    const val ADD_FIELD = "item:field:add"
    const val ADD_FIELD_LABEL = "item:field:add:label"
    const val ADD_FIELD_CONFIRM = "item:field:add:confirm"
    const val DISCARD = "item:discard"
    fun field(key: String) = "item:field:$key"
    fun reveal(key: String) = "item:field:$key:reveal"
    fun remove(key: String) = "item:field:$key:remove"
    fun kind(kind: CustomFieldKind) = "item:field:kind:${kind.name}"
}

@Composable
fun ItemEditorRoute(
    onBack: () -> Unit,
    onSaved: (uuid: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ItemEditorScreen(
        state = state,
        actions = ItemEditorActions(
            onBack = onBack,
            onTitleChange = viewModel::onTitleChange,
            onNoteChange = viewModel::onNoteChange,
            onFieldChange = viewModel::onFieldChange,
            onToggleFavorite = viewModel::toggleFavorite,
            onAddField = viewModel::addField,
            onRemoveField = viewModel::removeField,
            onSave = { viewModel.save(onSaved) },
        ),
        modifier = modifier,
    )
}

/** Everything the editor can ask for; defaults are no-ops for tests. */
data class ItemEditorActions(
    val onBack: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onFieldChange: (key: String, value: String) -> Unit = { _, _ -> },
    val onToggleFavorite: () -> Unit = {},
    val onAddField: (CustomFieldKind, label: String) -> Unit = { _, _ -> },
    val onRemoveField: (key: String) -> Unit = {},
    val onSave: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorScreen(
    state: ItemEditorUiState,
    actions: ItemEditorActions,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var addingField by remember { mutableStateOf(false) }

    // Both the toolbar arrow and system back go through here, so neither can drop edits.
    val leave = { if (state.isDirty) confirmDiscard = true else actions.onBack() }
    BackHandler(enabled = state.isDirty, onBack = leave)

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
                            state.missing || state.loading -> "Item"
                            state.isNew -> "New ${TemplateCatalog.displayName(state.template).lowercase()}"
                            else -> "Edit ${TemplateCatalog.displayName(state.template).lowercase()}"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.missing && !state.loading) {
                        IconToggleButton(
                            checked = state.favorite,
                            onCheckedChange = { actions.onToggleFavorite() },
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
                            onClick = actions.onSave,
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

            state.missing -> ItemGone(onBack = actions.onBack, modifier = Modifier.padding(padding))

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
                    onValueChange = actions.onTitleChange,
                    label = { Text("Title") },
                    singleLine = true,
                    isError = state.error != null && state.title.isBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ItemEditorTags.TITLE),
                )

                state.fields.forEach { field ->
                    if (field.isSection) {
                        SectionHeadingEditor(field, onRemove = { actions.onRemoveField(field.key) })
                    } else {
                        ItemFieldEditor(
                            field = field,
                            onValueChange = { actions.onFieldChange(field.key, it) },
                            onRemove = { actions.onRemoveField(field.key) },
                        )
                        if (field.type == FieldType.TOTP && field.value.isNotBlank()) {
                            TotpCodePanel(secret = field.value)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { addingField = true },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ItemEditorTags.ADD_FIELD),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Add field", modifier = Modifier.padding(start = 8.dp))
                }

                OutlinedTextField(
                    value = state.note,
                    onValueChange = actions.onNoteChange,
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
                    onClick = actions.onSave,
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

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this item have not been saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        actions.onBack()
                    },
                    modifier = Modifier.testTag(ItemEditorTags.DISCARD),
                ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (addingField) {
        AddFieldDialog(
            onAdd = { kind, label ->
                addingField = false
                actions.onAddField(kind, label)
            },
            onDismiss = { addingField = false },
        )
    }
}

@Composable
private fun ItemFieldEditor(
    field: EditableField,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    // The field's own flag decides, not its type: a "Hidden text" field is a sensitive TEXT.
    val masked = field.sensitive
    var revealed by remember(field.key) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = { Text(field.label) },
            singleLine = field.type != FieldType.MULTILINE,
            minLines = if (field.type == FieldType.MULTILINE) 3 else 1,
            shape = MaterialTheme.shapes.medium,
            textStyle = if (masked && revealed) SecretTextStyle else MaterialTheme.typography.bodyLarge,
            visualTransformation = if (masked && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardFor(field.type, masked),
                imeAction = if (field.type == FieldType.MULTILINE) ImeAction.Default else ImeAction.Next,
            ),
            trailingIcon = if (masked) {
                {
                    TextButton(
                        onClick = { revealed = !revealed },
                        modifier = Modifier.testTag(ItemEditorTags.reveal(field.key)),
                    ) {
                        Text(if (revealed) "Hide" else "Show")
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .weight(1f)
                .testTag(ItemEditorTags.field(field.key)),
        )
        IconButton(onClick = onRemove, modifier = Modifier.testTag(ItemEditorTags.remove(field.key))) {
            Icon(
                Icons.Outlined.RemoveCircleOutline,
                contentDescription = "Remove ${field.label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeadingEditor(field: EditableField, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(ItemEditorTags.field(field.key)),
    ) {
        Text(
            field.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.testTag(ItemEditorTags.remove(field.key))) {
            Icon(
                Icons.Outlined.RemoveCircleOutline,
                contentDescription = "Remove heading ${field.label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddFieldDialog(
    onAdd: (CustomFieldKind, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(CustomFieldKind.TEXT) }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("Add field") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text(kind.displayName) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ItemEditorTags.ADD_FIELD_LABEL),
                )
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(CustomFieldKind.entries) { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == kind,
                                    role = Role.RadioButton,
                                    onClick = { kind = option },
                                )
                                .padding(vertical = 2.dp)
                                .testTag(ItemEditorTags.kind(option)),
                        ) {
                            RadioButton(selected = option == kind, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(option.displayName)
                                if (option.sensitive) {
                                    Text(
                                        "Hidden and never searchable",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(kind, label) },
                modifier = Modifier.testTag(ItemEditorTags.ADD_FIELD_CONFIRM),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ItemGone(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
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
}

private fun keyboardFor(type: FieldType, masked: Boolean): KeyboardType = when (type) {
    FieldType.PIN, FieldType.CARD_PIN, FieldType.CARD_CVC -> KeyboardType.NumberPassword
    FieldType.NUMERIC, FieldType.CARD_NUMBER -> if (masked) KeyboardType.NumberPassword else KeyboardType.Number
    FieldType.EMAIL -> KeyboardType.Email
    FieldType.URL -> KeyboardType.Uri
    FieldType.PHONE -> KeyboardType.Phone
    else -> if (masked) KeyboardType.Password else KeyboardType.Text
}
