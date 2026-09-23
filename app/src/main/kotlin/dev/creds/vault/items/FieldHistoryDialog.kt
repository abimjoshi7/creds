package dev.creds.vault.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.creds.vault.core.model.FieldHistoryEntry
import dev.creds.vault.core.ui.theme.SecretTextStyle
import java.text.DateFormat
import java.util.Date

object FieldHistoryTags {
    const val CLOSE = "history:close"
    fun reveal(id: Long) = "history:reveal:$id"
    fun copy(id: Long) = "history:copy:$id"
    fun value(id: Long) = "history:value:$id"
}

/** What the history dialog is showing, if anything. */
data class FieldHistoryState(
    val fieldUid: Long,
    val fieldLabel: String,
    val loading: Boolean = true,
    val entries: List<FieldHistoryEntry> = emptyList(),
)

/**
 * Previous values of one field, newest first.
 *
 * Every value starts hidden and only one is revealed at a time: this is the screen people
 * open when the current password does not work, and several old passwords on screen at
 * once is more than that question needs.
 */
@Composable
fun FieldHistoryDialog(
    state: FieldHistoryState,
    onCopy: (label: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var revealedId by remember(state.fieldUid) { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("Previous ${state.fieldLabel.lowercase()}") },
        text = {
            when {
                state.loading -> CircularProgressIndicator()
                state.entries.isEmpty() -> Text(
                    "No previous values.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.entries, key = FieldHistoryEntry::id) { entry ->
                        val shown = revealedId == entry.id
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Replaced ${dateFormat.format(Date(entry.replacedAt))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (shown) entry.value else MASK,
                                    style = if (shown) SecretTextStyle else MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(FieldHistoryTags.value(entry.id)),
                                )
                                TextButton(
                                    onClick = { revealedId = if (shown) null else entry.id },
                                    modifier = Modifier.testTag(FieldHistoryTags.reveal(entry.id)),
                                ) { Text(if (shown) "Hide" else "Show") }
                                TextButton(
                                    onClick = { onCopy("Previous ${state.fieldLabel.lowercase()}", entry.value) },
                                    modifier = Modifier.testTag(FieldHistoryTags.copy(entry.id)),
                                ) { Text("Copy") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(FieldHistoryTags.CLOSE)) {
                Text("Close")
            }
        },
    )
}

/** Fixed width whatever the value, so a masked secret does not give away its length. */
internal const val MASK = "••••••••"
