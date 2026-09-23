package dev.creds.vault.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.AssociationKind
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.ItemAssociation
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import dev.creds.vault.core.ui.components.QuietPanel
import dev.creds.vault.core.ui.theme.CredsRadiusMedium
import dev.creds.vault.core.ui.theme.SecretTextStyle
import dev.creds.vault.ui.SensitiveClipboard
import dev.creds.vault.ui.TagLabel
import dev.creds.vault.ui.icon
import dev.creds.vault.ui.rememberCopySensitive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object ItemDetailTags {
    const val TITLE = "detail:title"
    const val CONTENT = "detail:content"
    const val EDIT = "detail:edit"
    const val FAVORITE = "detail:favorite"
    const val MENU = "detail:menu"
    const val NOTE = "detail:note"
    const val NOTE_COPY = "detail:note:copy"
    const val TRASH_BANNER = "detail:banner:trash"
    const val ARCHIVE_BANNER = "detail:banner:archive"
    fun value(uid: Long) = "detail:field:$uid"
    fun reveal(uid: Long) = "detail:field:$uid:reveal"
    fun copy(uid: Long) = "detail:field:$uid:copy"
    fun history(uid: Long) = "detail:field:$uid:history"
    fun menu(action: String) = "detail:menu:$action"
    fun association(value: String) = "detail:autofill:$value"
    fun removeAssociation(value: String) = "detail:autofill:$value:remove"
}

@Composable
fun ItemDetailRoute(
    onBack: () -> Unit,
    onEdit: (uuid: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val attachmentView by viewModel.attachmentView.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copySensitive = rememberCopySensitive()
    var tagging by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ItemDetailEvent.Trashed, ItemDetailEvent.Deleted -> onBack()
            }
        }
    }

    ItemDetailScreen(
        state = state,
        actions = ItemDetailActions(
            onBack = onBack,
            onEdit = { state.item?.let { onEdit(it.uuid) } },
            onFavorite = viewModel::setFavorite,
            onArchive = viewModel::setArchived,
            onTrash = viewModel::trash,
            onRestore = viewModel::restore,
            onDeleteForever = viewModel::deleteForever,
            onEditTags = { tagging = true },
            onOpenHistory = viewModel::openHistory,
            onDismissHistory = viewModel::dismissHistory,
            onRemoveAssociation = viewModel::removeAssociation,
            onCopy = { label, value ->
                copySensitive(label, value)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        "$label copied. Clears in ${SensitiveClipboard.CLEAR_AFTER_MS / 1000} seconds.",
                        duration = SnackbarDuration.Short,
                    )
                }
            },
        ),
        snackbarHostState = snackbar,
        modifier = modifier,
        attachmentView = attachmentView,
        attachmentActions = viewModel.attachmentActions,
    )

    val item = state.item
    if (tagging && item != null) {
        TagPickerDialog(
            itemTitle = item.title,
            allTags = state.allTags,
            initiallySelected = item.tags.mapTo(HashSet(), Tag::id),
            onCreateTag = viewModel::createTag,
            onConfirm = { ids ->
                viewModel.setTags(ids)
                tagging = false
            },
            onDismiss = { tagging = false },
        )
    }
}

/** Everything the item view can ask for; defaults are no-ops for tests. */
data class ItemDetailActions(
    val onBack: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onFavorite: (Boolean) -> Unit = {},
    val onArchive: (Boolean) -> Unit = {},
    val onTrash: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onDeleteForever: () -> Unit = {},
    val onEditTags: () -> Unit = {},
    val onOpenHistory: (VaultField) -> Unit = {},
    val onDismissHistory: () -> Unit = {},
    val onRemoveAssociation: (ItemAssociation) -> Unit = {},
    val onCopy: (label: String, value: String) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    state: ItemDetailUiState,
    actions: ItemDetailActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    attachmentView: AttachmentViewState = AttachmentViewState(),
    attachmentActions: AttachmentViewActions = AttachmentViewActions(),
) {
    val item = state.item
    var confirmPurge by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {},
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (item != null) {
                        DetailActions(item, actions, onDeleteForever = { confirmPurge = true })
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.missing -> ItemGone(onBack = actions.onBack, modifier = Modifier.padding(padding))

            item == null -> Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> ItemDetailContent(
                item = item,
                historyCounts = state.historyCounts,
                associations = state.associations,
                actions = actions,
                attachmentActions = attachmentActions,
                contentPadding = padding,
            )
        }
    }

    state.history?.let { history ->
        FieldHistoryDialog(state = history, onCopy = actions.onCopy, onDismiss = actions.onDismissHistory)
    }

    AttachmentDialogs(attachmentView, attachmentActions, snackbarHostState)

    if (confirmPurge && item != null) {
        ConfirmDialog(
            title = "Delete “${item.title}” forever?",
            text = "This removes the item and its history from this device. It cannot be undone.",
            confirmLabel = "Delete forever",
            onConfirm = {
                confirmPurge = false
                actions.onDeleteForever()
            },
            onDismiss = { confirmPurge = false },
        )
    }
}

@Composable
private fun DetailActions(item: VaultItem, actions: ItemDetailActions, onDeleteForever: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun run(action: () -> Unit) {
        menu = false
        action()
    }

    if (!item.trashed) {
        IconToggleButton(
            checked = item.favorite,
            onCheckedChange = actions.onFavorite,
            modifier = Modifier.testTag(ItemDetailTags.FAVORITE),
        ) {
            Icon(
                if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (item.favorite) "Remove from favorites" else "Add to favorites",
                tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = actions.onEdit, modifier = Modifier.testTag(ItemDetailTags.EDIT)) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
        }
    }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ItemDetailTags.MENU)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (item.trashed) {
                DropdownMenuItem(
                    text = { Text("Restore") },
                    leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                    modifier = Modifier.testTag(ItemDetailTags.menu("restore")),
                    onClick = { run(actions.onRestore) },
                )
                DropdownMenuItem(
                    text = { Text("Delete forever") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) },
                    modifier = Modifier.testTag(ItemDetailTags.menu("purge")),
                    onClick = { run(onDeleteForever) },
                )
                return@DropdownMenu
            }
            DropdownMenuItem(
                text = { Text("Tags…") },
                leadingIcon = { Icon(Icons.Outlined.Tag, null) },
                modifier = Modifier.testTag(ItemDetailTags.menu("tags")),
                onClick = { run(actions.onEditTags) },
            )
            DropdownMenuItem(
                text = { Text(if (item.archived) "Unarchive" else "Archive") },
                leadingIcon = {
                    Icon(if (item.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null)
                },
                modifier = Modifier.testTag(ItemDetailTags.menu(if (item.archived) "unarchive" else "archive")),
                onClick = { run { actions.onArchive(!item.archived) } },
            )
            DropdownMenuItem(
                text = { Text("Move to trash") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                modifier = Modifier.testTag(ItemDetailTags.menu("trash")),
                onClick = { run(actions.onTrash) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemDetailContent(
    item: VaultItem,
    historyCounts: Map<Long, Int>,
    associations: List<ItemAssociation>,
    actions: ItemDetailActions,
    attachmentActions: AttachmentViewActions,
    contentPadding: PaddingValues,
) {
    val rows = remember(item) { item.detailRows() }
    val attachments = remember(item) { item.attachments.map(AttachmentRow::from) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    LazyColumn(
        modifier = Modifier
            .padding(contentPadding)
            .fillMaxSize()
            .testTag(ItemDetailTags.CONTENT),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(CredsRadiusMedium),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            item.template.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag(ItemDetailTags.TITLE),
                    )
                    Text(
                        TemplateCatalog.displayName(item.template),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (item.tags.isNotEmpty()) {
            item(key = "tags") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.tags.forEach { TagLabel(it) }
                }
            }
        }

        if (item.trashed) {
            item(key = "trash-banner") {
                StatusBanner(
                    text = "This item is in the trash.",
                    action = "Restore",
                    onAction = actions.onRestore,
                    modifier = Modifier.testTag(ItemDetailTags.TRASH_BANNER),
                )
            }
        } else if (item.archived) {
            item(key = "archive-banner") {
                StatusBanner(
                    text = "Archived. Hidden from the main list.",
                    action = "Unarchive",
                    onAction = { actions.onArchive(false) },
                    modifier = Modifier.testTag(ItemDetailTags.ARCHIVE_BANNER),
                )
            }
        }

        items(rows, key = DetailRow::key) { row ->
            when (row) {
                is DetailRow.Section -> Text(
                    row.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )

                is DetailRow.OneTimeCode -> TotpCodePanel(
                    secret = row.field.value,
                    onCopy = actions.onCopy,
                )

                is DetailRow.Value -> FieldValueRow(
                    field = row.field,
                    historyCount = historyCounts[row.field.uid] ?: 0,
                    onCopy = { actions.onCopy(row.field.label, row.field.value) },
                    onOpenHistory = { actions.onOpenHistory(row.field) },
                )
            }
        }

        if (item.note.isNotEmpty()) {
            item(key = "note") {
                QuietPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Notes",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { actions.onCopy("Notes", item.note) },
                            modifier = Modifier.testTag(ItemDetailTags.NOTE_COPY),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy notes")
                        }
                    }
                    Text(
                        item.note,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(ItemDetailTags.NOTE),
                    )
                }
            }
        }

        if (attachments.isNotEmpty()) {
            item(key = "attachments-heading") {
                Text(
                    "Attachments",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(attachments, key = { "attachment:${it.id}" }) { row ->
                AttachmentListRow(
                    row = row,
                    onPreview = { attachmentActions.onPreview(row) },
                    onExport = { attachmentActions.onRequestExport(row) },
                )
            }
        }

        if (associations.isNotEmpty()) {
            item(key = "autofill-heading") {
                Text(
                    "Autofill",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(associations, key = { "autofill:${it.kind}:${it.value}" }) { association ->
                AssociationRow(association, onRemove = { actions.onRemoveAssociation(association) })
            }
        }

        item(key = "dates") {
            Column(Modifier.padding(top = 8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Created ${dateFormat.format(Date(item.createdAt))} · Updated ${dateFormat.format(Date(item.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                )
            }
        }
    }
}

/**
 * A label, its value, and what can be done with it.
 *
 * A sensitive value starts masked every time the row is composed — the reveal flag is
 * [remember]ed, not saved, for the reasons `PasswordField` gives — and the mask is the
 * same width whatever the secret's length.
 */
@Composable
private fun FieldValueRow(
    field: VaultField,
    historyCount: Int,
    onCopy: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    var revealed by remember(field.uid) { mutableStateOf(false) }
    val masked = field.sensitive && !revealed

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            field.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (masked) MASK else field.value,
                style = if (field.sensitive && revealed) SecretTextStyle else MaterialTheme.typography.bodyLarge,
                maxLines = if (field.type == FieldType.MULTILINE || !masked) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ItemDetailTags.value(field.uid)),
            )
            if (field.sensitive) {
                IconButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.testTag(ItemDetailTags.reveal(field.uid)),
                ) {
                    Icon(
                        if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (revealed) "Hide ${field.label}" else "Show ${field.label}",
                    )
                }
            }
            IconButton(onClick = onCopy, modifier = Modifier.testTag(ItemDetailTags.copy(field.uid))) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ${field.label}")
            }
        }
        if (historyCount > 0) {
            TextButton(
                onClick = onOpenHistory,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.testTag(ItemDetailTags.history(field.uid)),
            ) {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    if (historyCount == 1) "1 previous value" else "$historyCount previous values",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** A site or app this item fills, named the way the user knows it. */
@Composable
private fun AssociationRow(association: ItemAssociation, onRemove: () -> Unit) {
    val context = LocalContext.current
    val (title, detail) = when (association.kind) {
        AssociationKind.DOMAIN -> association.value to "Website"
        AssociationKind.APP -> {
            val label = remember(association.value) {
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(association.value, 0)).toString()
                }.getOrDefault(association.value)
            }
            val key = association.certSha256?.substringBefore(',')?.take(16)?.chunked(4)?.joinToString(" ")
            label to listOfNotNull(association.value, key?.let { "key $it…" }).joinToString(" · ")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(ItemDetailTags.association(association.value))) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.testTag(ItemDetailTags.removeAssociation(association.value))) {
            Icon(Icons.Outlined.LinkOff, contentDescription = "Stop filling in $title")
        }
    }
}

@Composable
private fun StatusBanner(text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    QuietPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
