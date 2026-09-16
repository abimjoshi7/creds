package dev.creds.vault.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.creds.vault.core.data.repository.TagResult
import dev.creds.vault.core.model.Tag
import dev.creds.vault.tags.message
import dev.creds.vault.ui.TagDot

object TagPickerTags {
    const val NEW_NAME = "tagpicker:new"
    const val ADD = "tagpicker:add"
    const val SAVE = "tagpicker:save"
    fun option(id: Long) = "tagpicker:option:$id"
}

/**
 * Chooses which tags an item carries, with a way to create one on the spot.
 *
 * Changes apply only on Save. A tag created here exists from the moment it is created —
 * cancelling does not delete it — but it is only put on the item if Save is pressed.
 */
@Composable
fun TagPickerDialog(
    itemTitle: String,
    allTags: List<Tag>,
    initiallySelected: Set<Long>,
    onCreateTag: (name: String, onResult: (TagResult) -> Unit) -> Unit,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun create() {
        onCreateTag(newName) { result ->
            when (result) {
                is TagResult.Saved -> {
                    selected = selected + result.tag.id
                    newName = ""
                    error = null
                }

                else -> error = result.message()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags for “$itemTitle”", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allTags.isEmpty()) {
                    Text(
                        "No tags yet. Tags are yours to define — work, family, a project.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(allTags, key = Tag::id) { tag ->
                        val checked = tag.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        selected = if (it) selected + tag.id else selected - tag.id
                                    },
                                )
                                .padding(vertical = 4.dp)
                                .testTag(TagPickerTags.option(tag.id)),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            TagDot(tag.color, size = 10.dp)
                            Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        error = null
                    },
                    label = { Text("New tag") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newName.isNotBlank()) create() }),
                    trailingIcon = {
                        IconButton(
                            onClick = ::create,
                            enabled = newName.isNotBlank(),
                            modifier = Modifier.testTag(TagPickerTags.ADD),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "Create tag")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TagPickerTags.NEW_NAME),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                modifier = Modifier.testTag(TagPickerTags.SAVE),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
