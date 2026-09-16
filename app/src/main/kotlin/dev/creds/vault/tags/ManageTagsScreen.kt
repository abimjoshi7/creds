package dev.creds.vault.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.tag.TagNames
import dev.creds.vault.core.model.Tag
import dev.creds.vault.items.ConfirmDialog
import dev.creds.vault.items.itemCount
import dev.creds.vault.ui.ColorSwatch
import dev.creds.vault.ui.TagDot
import dev.creds.vault.ui.TagPalette

object ManageTagsTags {
    const val ADD = "tags:add"
    const val EMPTY = "tags:empty"
    const val NAME = "tags:editor:name"
    const val SAVE = "tags:editor:save"
    fun row(id: Long) = "tags:row:$id"
    fun delete(id: Long) = "tags:row:$id:delete"
    fun swatch(color: Int?) = "tags:editor:swatch:${color ?: "none"}"
}

@Composable
fun ManageTagsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageTagsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ManageTagsScreen(
        state = state,
        onBack = onBack,
        onAdd = viewModel::startCreate,
        onEdit = viewModel::startEdit,
        onDelete = viewModel::delete,
        onNameChange = viewModel::onNameChange,
        onColorChange = viewModel::onColorChange,
        onSave = viewModel::saveEditor,
        onDismissEditor = viewModel::dismissEditor,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageTagsScreen(
    state: ManageTagsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Tag) -> Unit,
    onDelete: (Tag) -> Unit,
    onNameChange: (String) -> Unit,
    onColorChange: (Int?) -> Unit,
    onSave: () -> Unit,
    onDismissEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf<Tag?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New tag") },
                modifier = Modifier.testTag(ManageTagsTags.ADD),
            )
        },
    ) { padding ->
        val tags = state.tags
        when {
            tags == null -> Unit
            tags.isEmpty() -> Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(32.dp)
                    .testTag(ManageTagsTags.EMPTY),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No tags yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tags file items however you think about them — work, family, a trip. " +
                        "An item can carry several.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                // Keeps the last row clear of the floating button.
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(tags, key = Tag::id) { tag ->
                    ListItem(
                        modifier = Modifier
                            .clickable { onEdit(tag) }
                            .testTag(ManageTagsTags.row(tag.id)),
                        leadingContent = { TagDot(tag.color, size = 16.dp) },
                        headlineContent = {
                            Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = { Text(itemCount(state.counts[tag.id] ?: 0)) },
                        trailingContent = {
                            IconButton(
                                onClick = { confirmDelete = tag },
                                modifier = Modifier.testTag(ManageTagsTags.delete(tag.id)),
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete ${tag.name}")
                            }
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        TagEditorDialog(
            editor = editor,
            onNameChange = onNameChange,
            onColorChange = onColorChange,
            onSave = onSave,
            onDismiss = onDismissEditor,
        )
    }

    confirmDelete?.let { tag ->
        val count = state.counts[tag.id] ?: 0
        ConfirmDialog(
            title = "Delete “${tag.name}”?",
            text = if (count == 0) {
                "No items carry this tag."
            } else {
                "It will be removed from ${itemCount(count)}. The items themselves are kept."
            },
            confirmLabel = "Delete tag",
            onConfirm = {
                onDelete(tag)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditorDialog(
    editor: TagEditorState,
    onNameChange: (String) -> Unit,
    onColorChange: (Int?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isNew) "New tag" else "Edit tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = editor.error != null,
                    supportingText = {
                        val length = TagNames.normalize(editor.name).let { it.codePointCount(0, it.length) }
                        Text(editor.error ?: "$length/${TagNames.MAX_LENGTH}")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (editor.canSave) onSave() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ManageTagsTags.NAME),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (listOf<Int?>(null) + TagPalette.colors).forEach { color ->
                        ColorSwatch(
                            color = color,
                            selected = editor.color == color,
                            modifier = Modifier
                                .selectable(
                                    selected = editor.color == color,
                                    role = Role.RadioButton,
                                    onClick = { onColorChange(color) },
                                )
                                .semantics {
                                    contentDescription =
                                        if (color == null) "Default colour" else "Colour ${TagPalette.colors.indexOf(color) + 1}"
                                }
                                .testTag(ManageTagsTags.swatch(color)),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = editor.canSave,
                modifier = Modifier.testTag(ManageTagsTags.SAVE),
            ) { Text(if (editor.isNew) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
