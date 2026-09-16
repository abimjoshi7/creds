package dev.creds.vault.items

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultItemSummary
import dev.creds.vault.ui.TagDot
import dev.creds.vault.ui.TagLabel
import dev.creds.vault.ui.icon
import dev.creds.vault.ui.label
import kotlinx.coroutines.launch

/** Stable handles for the UI tests. See `SetupTags` for why these exist. */
object VaultListTags {
    const val SEARCH = "vault:search"
    const val SEARCH_CLEAR = "vault:search:clear"
    const val DRAWER_BUTTON = "vault:drawer"
    const val OVERFLOW = "vault:overflow"
    const val LOCK = "vault:lock"
    const val LIST = "vault:list"
    const val EMPTY = "vault:empty"
    const val CLEAR_FILTERS = "vault:filters:clear"

    fun item(uuid: String) = "vault:item:$uuid"
    fun favorite(uuid: String) = "vault:item:$uuid:favorite"
    fun itemMenu(uuid: String) = "vault:item:$uuid:menu"
    fun smartList(list: SmartList) = "vault:drawer:list:${list.name}"
    fun template(template: Template) = "vault:drawer:template:${template.id}"
    fun tag(id: Long) = "vault:drawer:tag:$id"
    fun filterChip(key: String) = "vault:filter:$key"
    fun menu(action: String) = "vault:menu:$action"
    const val CONFIRM = "vault:confirm"
}

/** The smart lists this build can answer. Weak, reused and breached arrive with the audit. */
val DrawerSmartLists: List<SmartList> =
    listOf(SmartList.ALL, SmartList.FAVORITES, SmartList.ARCHIVE, SmartList.TRASH)

/**
 * Everything the list screen can ask for.
 *
 * Grouped into one object so the screen stays testable without a ViewModel, and without
 * a constructor call site listing twenty lambdas. Defaults are no-ops for tests.
 */
data class VaultListActions(
    val onQueryChange: (String) -> Unit = {},
    val onSmartList: (SmartList) -> Unit = {},
    val onTemplate: (Template) -> Unit = {},
    val onTag: (Long) -> Unit = {},
    val onResetFilters: () -> Unit = {},
    val onFavorite: (VaultItemSummary, Boolean) -> Unit = { _, _ -> },
    val onArchive: (VaultItemSummary) -> Unit = {},
    val onUnarchive: (VaultItemSummary) -> Unit = {},
    val onTrash: (VaultItemSummary) -> Unit = {},
    val onRestore: (VaultItemSummary) -> Unit = {},
    val onDeleteForever: (VaultItemSummary) -> Unit = {},
    val onEmptyTrash: () -> Unit = {},
    val onEditTags: (VaultItemSummary) -> Unit = {},
    val onManageTags: () -> Unit = {},
    val onAddSamples: (() -> Unit)? = null,
    val onLock: () -> Unit = {},
)

@Composable
fun VaultListRoute(
    onManageTags: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VaultListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tagging by remember { mutableStateOf<VaultItemSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultListEvent.Trashed -> {
                    val result = snackbar.showSnackbar(
                        "Moved “${event.item.title}” to trash",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.item)
                }

                is VaultListEvent.Archived -> {
                    val result = snackbar.showSnackbar(
                        "Archived “${event.item.title}”",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.unarchive(event.item)
                }

                is VaultListEvent.TrashEmptied ->
                    snackbar.showSnackbar(itemCount(event.count, "deleted forever"))

                is VaultListEvent.SamplesAdded ->
                    snackbar.showSnackbar("Added ${event.count} sample items")
            }
        }
    }

    VaultListScreen(
        state = state,
        actions = VaultListActions(
            onQueryChange = viewModel::onQueryChange,
            onSmartList = viewModel::selectSmartList,
            onTemplate = viewModel::toggleTemplate,
            onTag = viewModel::toggleTag,
            onResetFilters = viewModel::resetFilters,
            onFavorite = viewModel::setFavorite,
            onArchive = viewModel::archive,
            onUnarchive = viewModel::unarchive,
            onTrash = viewModel::trash,
            onRestore = viewModel::restore,
            onDeleteForever = viewModel::deleteForever,
            onEmptyTrash = viewModel::emptyTrash,
            onEditTags = { tagging = it },
            onManageTags = onManageTags,
            onAddSamples = viewModel::addSamples.takeIf { viewModel.canAddSamples },
            onLock = onLock,
        ),
        snackbarHostState = snackbar,
        modifier = modifier,
    )

    tagging?.let { item ->
        TagPickerDialog(
            itemTitle = item.title,
            allTags = state.tags,
            initiallySelected = item.tags.mapTo(HashSet(), Tag::id),
            onCreateTag = viewModel::createTag,
            onConfirm = { ids ->
                viewModel.setTags(item, ids)
                tagging = null
            },
            onDismiss = { tagging = null },
        )
    }
}

@Composable
fun VaultListScreen(
    state: VaultListUiState,
    actions: VaultListActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeDrawer() = scope.launch { drawerState.close() }

    var confirmPurge by remember { mutableStateOf<VaultItemSummary?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet {
                VaultDrawerContent(
                    state = state,
                    onSmartList = {
                        actions.onSmartList(it)
                        closeDrawer()
                    },
                    onTemplate = {
                        actions.onTemplate(it)
                        closeDrawer()
                    },
                    // Stays open: tags combine, so picking a second one is the common case.
                    onTag = actions.onTag,
                    onManageTags = {
                        closeDrawer()
                        actions.onManageTags()
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                SearchTopBar(
                    query = state.filter.query,
                    placeholder = "Search ${state.filter.smartList.label.lowercase()}",
                    onQueryChange = actions.onQueryChange,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onLock = actions.onLock,
                    inTrash = state.filter.smartList == SmartList.TRASH,
                    canEmptyTrash = state.counts.trash > 0,
                    onEmptyTrash = { confirmEmptyTrash = true },
                    onManageTags = actions.onManageTags,
                    onAddSamples = actions.onAddSamples,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                ActiveFilters(state = state, actions = actions)

                val items = state.items
                when {
                    // Still loading. Blank rather than a spinner: the query is local and
                    // usually finishes before a spinner would even be noticed.
                    items == null -> Unit
                    items.isEmpty() -> EmptyState(state = state, actions = actions)
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(VaultListTags.LIST),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(items, key = VaultItemSummary::uuid) { item ->
                            ItemRow(
                                item = item,
                                actions = actions,
                                onDeleteForever = { confirmPurge = item },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmPurge?.let { item ->
        ConfirmDialog(
            title = "Delete “${item.title}” forever?",
            text = "This removes the item and its history from this device. It cannot be undone.",
            confirmLabel = "Delete forever",
            onConfirm = {
                actions.onDeleteForever(item)
                confirmPurge = null
            },
            onDismiss = { confirmPurge = null },
        )
    }

    if (confirmEmptyTrash) {
        ConfirmDialog(
            title = "Empty trash?",
            text = "${itemCount(state.counts.trash, "will be deleted")} forever. This cannot be undone.",
            confirmLabel = "Empty trash",
            onConfirm = {
                actions.onEmptyTrash()
                confirmEmptyTrash = false
            },
            onDismiss = { confirmEmptyTrash = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onLock: () -> Unit,
    inTrash: Boolean,
    canEmptyTrash: Boolean,
    onEmptyTrash: () -> Unit,
    onManageTags: () -> Unit,
    onAddSamples: (() -> Unit)?,
) {
    var overflow by remember { mutableStateOf(false) }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag(VaultListTags.DRAWER_BUTTON)) {
                Icon(Icons.Filled.Menu, contentDescription = "Open lists and tags")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.testTag(VaultListTags.SEARCH_CLEAR),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    // Search terms are titles and usernames, but autocorrect rewriting
                    // "gmial" to "gmail" hides the typo the person is looking for.
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp)
                    .testTag(VaultListTags.SEARCH),
            )
        },
        actions = {
            IconButton(onClick = onLock, modifier = Modifier.testTag(VaultListTags.LOCK)) {
                Icon(Icons.Outlined.Lock, contentDescription = "Lock vault")
            }
            Box {
                IconButton(
                    onClick = { overflow = true },
                    modifier = Modifier.testTag(VaultListTags.OVERFLOW),
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    if (inTrash) {
                        DropdownMenuItem(
                            text = { Text("Empty trash") },
                            modifier = Modifier.testTag(VaultListTags.menu("empty-trash")),
                            enabled = canEmptyTrash,
                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) },
                            onClick = {
                                overflow = false
                                onEmptyTrash()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Manage tags") },
                        leadingIcon = { Icon(Icons.Outlined.Tag, null) },
                        onClick = {
                            overflow = false
                            onManageTags()
                        },
                    )
                    onAddSamples?.let { addSamples ->
                        DropdownMenuItem(
                            text = { Text("Add sample items (debug)") },
                            onClick = {
                                overflow = false
                                addSamples()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun VaultDrawerContent(
    state: VaultListUiState,
    onSmartList: (SmartList) -> Unit,
    onTemplate: (Template) -> Unit,
    onTag: (Long) -> Unit,
    onManageTags: () -> Unit,
) {
    val filter = state.filter
    val counts = state.counts

    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item { DrawerHeading("Creds") }

        items(DrawerSmartLists) { list ->
            NavigationDrawerItem(
                label = { Text(list.label) },
                icon = { Icon(list.icon, contentDescription = null) },
                badge = { CountBadge(counts.of(list)) },
                selected = filter.smartList == list,
                onClick = { onSmartList(list) },
                modifier = Modifier.testTag(VaultListTags.smartList(list)),
            )
        }

        // Types with nothing in them are hidden: nine rows of zeros bury the ones in use.
        // The active one stays, so a filter can always be seen and cleared from here.
        val templates = Template.entries.filter {
            (counts.byTemplate[it] ?: 0) > 0 || filter.template == it
        }
        if (templates.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DrawerHeading("Types")
            }
            items(templates) { template ->
                NavigationDrawerItem(
                    label = { Text(TemplateCatalog.displayName(template)) },
                    icon = { Icon(template.icon, contentDescription = null) },
                    badge = { CountBadge(counts.byTemplate[template]) },
                    selected = filter.template == template,
                    onClick = { onTemplate(template) },
                    modifier = Modifier.testTag(VaultListTags.template(template)),
                )
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerHeading("Tags")
        }
        items(state.tags, key = Tag::id) { tag ->
            NavigationDrawerItem(
                label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { TagDot(tag.color, size = 12.dp) },
                badge = { CountBadge(counts.byTag[tag.id] ?: 0) },
                selected = tag.id in filter.tagIds,
                onClick = { onTag(tag.id) },
                modifier = Modifier.testTag(VaultListTags.tag(tag.id)),
            )
        }
        item {
            NavigationDrawerItem(
                label = { Text(if (state.tags.isEmpty()) "Create a tag" else "Manage tags") },
                icon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
                selected = false,
                onClick = onManageTags,
            )
        }
    }
}

@Composable
private fun DrawerHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun CountBadge(count: Int?) {
    if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelMedium)
}

/**
 * The filters narrowing the list, each removable in one tap.
 *
 * Shown whenever anything beyond "all items" applies. A filter that is only visible in a
 * closed drawer is how people conclude their items have vanished.
 */
@Composable
private fun ActiveFilters(state: VaultListUiState, actions: VaultListActions) {
    val filter = state.filter
    if (filter.smartList == SmartList.ALL && filter.template == null && filter.tagIds.isEmpty()) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (filter.smartList != SmartList.ALL) {
            item {
                FilterChipRemovable(
                    label = filter.smartList.label,
                    key = "list",
                    leading = { Icon(filter.smartList.icon, null, Modifier.size(InputChipDefaults.IconSize)) },
                    onRemove = { actions.onSmartList(SmartList.ALL) },
                )
            }
        }
        filter.template?.let { template ->
            item {
                FilterChipRemovable(
                    label = TemplateCatalog.displayName(template),
                    key = "template",
                    leading = { Icon(template.icon, null, Modifier.size(InputChipDefaults.IconSize)) },
                    onRemove = { actions.onTemplate(template) },
                )
            }
        }
        items(state.activeTags, key = { "tag:${it.id}" }) { tag ->
            FilterChipRemovable(
                label = tag.name,
                key = "tag:${tag.id}",
                leading = { TagDot(tag.color) },
                onRemove = { actions.onTag(tag.id) },
            )
        }
        item {
            TextButton(
                onClick = actions.onResetFilters,
                modifier = Modifier.testTag(VaultListTags.CLEAR_FILTERS),
            ) { Text("Clear") }
        }
    }
}

@Composable
private fun FilterChipRemovable(
    label: String,
    key: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leading,
        trailingIcon = {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove $label filter",
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
        modifier = Modifier.testTag(VaultListTags.filterChip(key)),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(
    item: VaultItemSummary,
    actions: VaultListActions,
    onDeleteForever: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    ListItem(
        // Opening an item is the next checkpoint; until then a tap offers what can be
        // done to it, which is also where it will keep living on long-press.
        modifier = Modifier
            .clickable { menu = true }
            .testTag(VaultListTags.item(item.uuid)),
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        item.template.icon,
                        contentDescription = TemplateCatalog.displayName(item.template),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (item.subtitle.isEmpty() && item.tags.isEmpty()) {
            null
        } else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.subtitle.isNotEmpty()) {
                        Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            maxLines = 1,
                        ) {
                            item.tags.forEach { TagLabel(it) }
                        }
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.trashed) {
                    IconToggleButton(
                        checked = item.favorite,
                        onCheckedChange = { actions.onFavorite(item, it) },
                        modifier = Modifier
                            .testTag(VaultListTags.favorite(item.uuid))
                            .semantics {
                                contentDescription =
                                    if (item.favorite) "Remove from favorites" else "Add to favorites"
                            },
                    ) {
                        Icon(
                            if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = null,
                            tint = if (item.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { menu = true },
                        modifier = Modifier.testTag(VaultListTags.itemMenu(item.uuid)),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Actions for ${item.title}")
                    }
                    ItemMenu(
                        item = item,
                        expanded = menu,
                        onDismiss = { menu = false },
                        actions = actions,
                        onDeleteForever = onDeleteForever,
                    )
                }
            }
        },
    )
}

@Composable
private fun ItemMenu(
    item: VaultItemSummary,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: VaultListActions,
    onDeleteForever: () -> Unit,
) {
    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (item.trashed) {
            DropdownMenuItem(
                text = { Text("Restore") },
                modifier = Modifier.testTag(VaultListTags.menu("restore")),
                leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                onClick = { run { actions.onRestore(item) } },
            )
            DropdownMenuItem(
                text = { Text("Delete forever") },
                modifier = Modifier.testTag(VaultListTags.menu("purge")),
                leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) },
                onClick = { run(onDeleteForever) },
            )
            return@DropdownMenu
        }

        DropdownMenuItem(
            text = { Text("Tags…") },
            modifier = Modifier.testTag(VaultListTags.menu("tags")),
            leadingIcon = { Icon(Icons.Outlined.Tag, null) },
            onClick = { run { actions.onEditTags(item) } },
        )
        if (item.archived) {
            DropdownMenuItem(
                text = { Text("Unarchive") },
                modifier = Modifier.testTag(VaultListTags.menu("unarchive")),
                leadingIcon = { Icon(Icons.Outlined.Unarchive, null) },
                onClick = { run { actions.onUnarchive(item) } },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Archive") },
                modifier = Modifier.testTag(VaultListTags.menu("archive")),
                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                onClick = { run { actions.onArchive(item) } },
            )
        }
        DropdownMenuItem(
            text = { Text("Move to trash") },
            modifier = Modifier.testTag(VaultListTags.menu("trash")),
            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
            onClick = { run { actions.onTrash(item) } },
        )
    }
}

/**
 * Says *why* the list is empty.
 *
 * "No items" when a filter is hiding everything reads as data loss, so each cause gets its
 * own message and, where there is one, the way out.
 */
@Composable
private fun EmptyState(state: VaultListUiState, actions: VaultListActions) {
    val filter = state.filter
    val (title, body) = when {
        state.counts.total == 0 ->
            "Your vault is empty" to "Items you add or import will appear here."
        filter.query.isNotBlank() ->
            "No matches for “${filter.query.trim()}”" to
                "Search covers titles, usernames, websites and notes. Passwords and other secrets are never searchable."
        filter.template != null || filter.tagIds.isNotEmpty() ->
            "Nothing matches these filters" to "Every selected filter must match."
        filter.smartList == SmartList.FAVORITES ->
            "No favorites yet" to "Star an item to keep it here."
        filter.smartList == SmartList.ARCHIVE ->
            "Nothing archived" to "Archived items are kept but stay out of the main list."
        filter.smartList == SmartList.TRASH ->
            "Trash is empty" to "Items you delete wait here until you empty the trash."
        else ->
            "No items" to "Everything in your vault is archived or in the trash."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(VaultListTags.EMPTY),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (filter.isRefined && state.counts.total > 0) {
            TextButton(onClick = actions.onResetFilters) { Text("Show all items") }
        }
        if (state.counts.total == 0) {
            actions.onAddSamples?.let { TextButton(onClick = it) { Text("Add sample items (debug)") } }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(VaultListTags.CONFIRM)) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun itemCount(count: Int, suffix: String = ""): String {
    val noun = if (count == 1) "1 item" else "$count items"
    return if (suffix.isEmpty()) noun else "$noun $suffix"
}
