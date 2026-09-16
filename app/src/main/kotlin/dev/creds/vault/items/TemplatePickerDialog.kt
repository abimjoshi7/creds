package dev.creds.vault.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.Template
import dev.creds.vault.ui.icon

object TemplatePickerTags {
    fun option(template: Template) = "template:pick:${template.id}"
}

@Composable
fun TemplatePickerDialog(
    onPick: (Template) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("New item") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(Template.entries, key = Template::id) { template ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(template) }
                            .testTag(TemplatePickerTags.option(template)),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        leadingContent = {
                            Icon(template.icon, contentDescription = null)
                        },
                        headlineContent = { Text(TemplateCatalog.displayName(template)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
