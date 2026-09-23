package dev.creds.vault.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.ui.components.QuietPanel
import dev.creds.vault.core.ui.theme.SeverityCritical

object AutofillTags {
    const val SEARCH = "autofill:search"
    const val CONFIRM = "autofill:confirm"
    const val REFUSED = "autofill:refused"
    const val SAVE_NEW = "autofill:save:new"
    const val SAVE_TITLE = "autofill:save:title"
    const val NOT_NOW = "autofill:save:not-now"
    fun row(uuid: String) = "autofill:row:$uuid"
    fun update(uuid: String) = "autofill:save:update:$uuid"
}

data class AutofillPickerActions(
    val onClose: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onPick: (uuid: String) -> Unit = {},
    val onConfirm: (uuid: String) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * Choosing an item to fill.
 *
 * Suggested items are ones already trusted here. Anything else can still be chosen, but
 * choosing it is a decision, so it is asked about — and what Creds will remember is
 * spelled out: the site, or the app and the key it is signed with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillPickerScreen(
    state: AutofillPickerUiState,
    appFingerprint: String?,
    actions: AutofillPickerActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Fill ${state.originName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                placeholder = { Text("Search your vault") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(AutofillTags.SEARCH),
            )
            val rows = state.rows
            if (rows == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.visibleRows, key = PickerRow::uuid) { row ->
                        PickerRowView(row, onClick = { actions.onPick(row.uuid) })
                    }
                }
            }
        }
    }

    state.confirm?.let { row ->
        AlertDialog(
            onDismissRequest = actions.onDismiss,
            shape = MaterialTheme.shapes.large,
            title = { Text("Use “${row.title}” here?") },
            text = {
                Text(
                    if (state.isWeb) {
                        "Vaultesque will remember ${state.originName} for this item and suggest it there from now on."
                    } else {
                        "Vaultesque will remember ${state.originName} for this item, identified by its signing key " +
                            "${appFingerprint.orEmpty()}. If a different app ever claims to be it, Vaultesque will not fill."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { actions.onConfirm(row.uuid) }, modifier = Modifier.testTag(AutofillTags.CONFIRM)) {
                    Text("Fill and remember")
                }
            },
            dismissButton = { TextButton(onClick = actions.onDismiss) { Text("Cancel") } },
        )
    }

    state.refused?.let { row ->
        AlertDialog(
            onDismissRequest = actions.onDismiss,
            shape = MaterialTheme.shapes.large,
            title = { Text("Not filling “${row.title}”") },
            text = {
                Text(
                    "This item is trusted for an app called ${state.originName}, but the app asking now is signed " +
                        "with a different key. It may not be the app it claims to be.",
                    modifier = Modifier.testTag(AutofillTags.REFUSED),
                )
            },
            confirmButton = { TextButton(onClick = actions.onDismiss) { Text("OK") } },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = actions.onDismiss,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = actions.onDismiss) { Text("OK") } },
        )
    }
}

@Composable
private fun PickerRowView(row: PickerRow, onClick: () -> Unit) {
    val refused = row.verdict == PickVerdict.CONFLICT
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag(AutofillTags.row(row.uuid)),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (row.verdict) {
                    PickVerdict.TRUSTED -> "Suggested here"
                    PickVerdict.CONFLICT -> "Trusted for a different app"
                    else -> row.subtitle.ifEmpty { TemplateCatalog.displayName(row.template) }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (refused) SeverityCritical else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.verdict == PickVerdict.TRUSTED) {
            Icon(Icons.Outlined.Verified, contentDescription = "Suggested", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

data class AutofillSaveActions(
    val onTitleChange: (String) -> Unit = {},
    val onSaveNew: () -> Unit = {},
    val onUpdate: (uuid: String) -> Unit = {},
    val onNotNow: () -> Unit = {},
)

/** "Save login for bank.com?" — or update the password of the login already saved there. */
@Composable
fun AutofillSaveScreen(
    state: AutofillSaveUiState,
    actions: AutofillSaveActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (!state.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Save login for ${state.originName}?", style = MaterialTheme.typography.headlineSmall)
            if (state.username.isNotEmpty()) {
                Text(state.username, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.matches.forEach { match ->
                OutlinedButton(
                    onClick = { actions.onUpdate(match.uuid) },
                    enabled = !state.busy,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag(AutofillTags.update(match.uuid)),
                ) { Text("Update password in “${match.title}”") }
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = actions.onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag(AutofillTags.SAVE_TITLE),
            )
            state.fingerprint?.let {
                QuietPanel {
                    Text(
                        "Vaultesque will fill this login in ${state.originName} only while it is signed with key $it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = actions.onSaveNew,
                enabled = !state.busy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag(AutofillTags.SAVE_NEW),
            ) { Text(if (state.matches.isEmpty()) "Save" else "Save as new item") }
            TextButton(onClick = actions.onNotNow, modifier = Modifier.fillMaxWidth().testTag(AutofillTags.NOT_NOW)) {
                Text("Not now")
            }
        }
    }
}
