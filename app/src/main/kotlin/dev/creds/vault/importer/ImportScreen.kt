package dev.creds.vault.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.importer.ImportPlanner
import dev.creds.vault.core.domain.importer.ImportRow
import dev.creds.vault.core.domain.importer.ImportStatus
import dev.creds.vault.core.domain.importer.ImportWarnings
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.ui.components.QuietPanel

object ImportTags {
    const val CHOOSE = "import:choose"
    const val COMMIT = "import:commit"
    const val SUMMARY = "import:summary"
    const val WARNINGS = "import:warnings"
    const val ERROR = "import:error"
    const val DONE = "import:done"
    fun row(index: Int) = "import:row:$index"
}

data class ImportActions(
    val onBack: () -> Unit = {},
    val onChoose: () -> Unit = {},
    val onToggle: (Int) -> Unit = {},
    val onCommit: () -> Unit = {},
    val onReset: () -> Unit = {},
)

@Composable
fun ImportRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The system picker hands back a content URI; the file itself is never copied.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFileChosen)
    }
    ImportScreen(
        state = state,
        actions = ImportActions(
            onBack = {
                viewModel.reset()
                onBack()
            },
            onChoose = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onToggle = viewModel::toggle,
            onCommit = viewModel::commit,
            onReset = viewModel::reset,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(state: ImportUiState, actions: ImportActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Import from Enpass") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (state.stage) {
            ImportStage.CHOOSE -> ChooseStage(state, actions, content)
            ImportStage.READING, ImportStage.IMPORTING -> Box(content, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ImportStage.PREVIEW -> PreviewStage(state, actions, content)
            ImportStage.DONE -> Column(
                content.padding(24.dp).testTag(ImportTags.DONE),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (state.imported == 1) "Imported 1 item." else "Imported ${state.imported} items.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Delete the Enpass export file now. It holds every password in plain text.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = actions.onBack, shape = MaterialTheme.shapes.medium) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ChooseStage(state: ImportUiState, actions: ImportActions, modifier: Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "In Enpass, export your vault as JSON, then choose the file here. You'll see what will be imported before anything is saved.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "The file is read straight into memory and never copied. Items already in your vault — same title and username — are left unticked.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ImportTags.ERROR))
        }
        Button(
            onClick = actions.onChoose,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag(ImportTags.CHOOSE),
        ) { Text("Choose export file") }
    }
}

@Composable
private fun PreviewStage(state: ImportUiState, actions: ImportActions, modifier: Modifier) {
    Column(modifier) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item(key = "summary") {
                Text(
                    buildString {
                        append(if (state.newCount == 1) "1 new item" else "${state.newCount} new items")
                        if (state.duplicateCount > 0) append(" · ${state.duplicateCount} already in your vault")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp).testTag(ImportTags.SUMMARY),
                )
            }
            if (!state.warnings.isEmpty) {
                item(key = "warnings") {
                    QuietPanel(Modifier.padding(bottom = 8.dp).testTag(ImportTags.WARNINGS)) {
                        warningLines(state.warnings).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            itemsIndexed(state.rows) { index, row -> ImportRowView(index, row, onToggle = { actions.onToggle(index) }) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onReset) { Text("Cancel") }
            Button(
                onClick = actions.onCommit,
                enabled = state.selectedCount > 0,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).testTag(ImportTags.COMMIT),
            ) { Text(if (state.selectedCount == 1) "Import 1 item" else "Import ${state.selectedCount} items") }
        }
    }
}

@Composable
private fun ImportRowView(index: Int, row: ImportRow, onToggle: () -> Unit) {
    val item = row.imported.item
    val username = ImportPlanner.usernameOf(item)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp)
            .testTag(ImportTags.row(index)),
    ) {
        Checkbox(checked = row.selected, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    TemplateCatalog.displayName(item.template),
                    username.takeIf { it.isNotEmpty() },
                    "already in vault".takeIf { row.status == ImportStatus.DUPLICATE },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (row.status == ImportStatus.DUPLICATE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What will not come across, in plain words. */
internal fun warningLines(warnings: ImportWarnings): List<String> = buildList {
    if (warnings.encryptedHistorySkipped > 0) add("${warnings.encryptedHistorySkipped} previous values Enpass kept encrypted can't be imported.")
    if (warnings.attachmentsSkipped > 0) add("${warnings.attachmentsSkipped} attachments are not imported; Creds does not store files.")
    if (warnings.deletedFieldsSkipped > 0) add("${warnings.deletedFieldsSkipped} deleted fields are left out.")
    if (warnings.unknownFieldTypes.isNotEmpty()) add("Unfamiliar field types (${warnings.unknownFieldTypes.joinToString()}) are imported as text.")
    if (warnings.unmappedTemplates.isNotEmpty()) add("Unfamiliar item types (${warnings.unmappedTemplates.joinToString()}) are imported as Other.")
}
