package dev.creds.vault.items

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultCounts
import dev.creds.vault.core.model.VaultFilter
import dev.creds.vault.core.model.VaultItemSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vault list, rendered in-process.
 *
 * Same constraints as the setup tests: `FLAG_SECURE` and the OEM block every
 * out-of-process way of seeing this screen.
 */
@RunWith(AndroidJUnit4::class)
class VaultListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val work = Tag(1, "work", color = 0xFF3B6FB6.toInt())
    private val gmail = VaultItemSummary(
        uuid = "a",
        template = Template.LOGIN,
        title = "Gmail",
        subtitle = "alice@example.com",
        favorite = true,
        tags = listOf(work),
    )
    private val visa = VaultItemSummary(uuid = "b", template = Template.CARD, title = "Visa")
    private val populated = VaultCounts(all = 2, favorites = 1, byTemplate = mapOf(Template.LOGIN to 1, Template.CARD to 1))

    @Test
    fun rendersRowsWithSubtitleAndTags() {
        setContent(VaultListUiState(items = listOf(gmail, visa), tags = listOf(work), counts = populated))

        compose.onNodeWithText("Gmail").assertIsDisplayed()
        compose.onNodeWithText("alice@example.com").assertIsDisplayed()
        compose.onNodeWithText("Visa").assertIsDisplayed()
        compose.onNodeWithTag(VaultListTags.item("a")).assertIsDisplayed()
        compose.onNodeWithTag(VaultListTags.item("a")).assert(hasText("work"))
    }

    @Test
    fun rendersNothingWhileLoading() {
        // An empty-vault message flashing before the first query returns reads as data loss.
        setContent(VaultListUiState(items = null))

        compose.onAllNodesWithTag(VaultListTags.EMPTY).assertCountEquals(0)
        compose.onAllNodesWithTag(VaultListTags.LIST).assertCountEquals(0)
    }

    @Test
    fun explainsAnEmptyVault() {
        setContent(VaultListUiState(items = emptyList()))

        compose.onNodeWithText("Your vault is empty").assertIsDisplayed()
    }

    @Test
    fun offersSamplesOnlyWhenProvided() {
        var added = false
        setContent(
            VaultListUiState(items = emptyList()),
            VaultListActions(onAddSamples = { added = true }),
        )

        compose.onNodeWithText("Add sample items (debug)").performClick()

        assertEquals(true, added)
    }

    @Test
    fun noSampleButtonWithoutTheAction() {
        setContent(VaultListUiState(items = emptyList()))

        compose.onAllNodesWithText("Add sample items (debug)").assertCountEquals(0)
    }

    @Test
    fun explainsANoMatchSearchAndThatSecretsAreNotSearchable() {
        setContent(
            VaultListUiState(filter = VaultFilter(query = "hunter2"), items = emptyList(), counts = populated),
        )

        compose.onNodeWithText("No matches for “hunter2”").assertIsDisplayed()
        compose.onNodeWithText(
            "Search covers titles, usernames, websites and notes. Passwords and other secrets are never searchable.",
        ).assertIsDisplayed()
    }

    @Test
    fun anEmptyFilteredListOffersAWayBack() {
        var reset = false
        setContent(
            VaultListUiState(filter = VaultFilter(template = Template.WIFI), items = emptyList(), counts = populated),
            VaultListActions(onResetFilters = { reset = true }),
        )

        compose.onNodeWithText("Nothing matches these filters").assertIsDisplayed()
        compose.onNodeWithText("Show all items").performClick()

        assertEquals(true, reset)
    }

    @Test
    fun emptyTrashHasItsOwnMessage() {
        setContent(
            VaultListUiState(filter = VaultFilter(smartList = SmartList.TRASH), items = emptyList(), counts = populated),
        )

        compose.onNodeWithText("Trash is empty").assertIsDisplayed()
    }

    @Test
    fun typingSearchReportsTheQuery() {
        var query = ""
        setContent(
            VaultListUiState(items = listOf(gmail), counts = populated),
            VaultListActions(onQueryChange = { query = it }),
        )

        compose.onNodeWithTag(VaultListTags.SEARCH).performTextInput("gm")

        assertEquals("gm", query)
    }

    @Test
    fun clearingSearchReportsAnEmptyQuery() {
        var query: String? = null
        setContent(
            VaultListUiState(filter = VaultFilter(query = "gm"), items = listOf(gmail), counts = populated),
            VaultListActions(onQueryChange = { query = it }),
        )

        compose.onNodeWithTag(VaultListTags.SEARCH_CLEAR).performClick()

        assertEquals("", query)
    }

    @Test
    fun activeFiltersAreVisibleAndRemovable() {
        var removedTag: Long? = null
        var removedTemplate: Template? = null
        setContent(
            VaultListUiState(
                filter = VaultFilter(template = Template.LOGIN, tagIds = setOf(work.id)),
                items = listOf(gmail),
                tags = listOf(work),
                counts = populated,
            ),
            VaultListActions(onTag = { removedTag = it }, onTemplate = { removedTemplate = it }),
        )

        compose.onNodeWithTag(VaultListTags.filterChip("template")).performClick()
        compose.onNodeWithTag(VaultListTags.filterChip("tag:1")).performClick()

        assertEquals(Template.LOGIN, removedTemplate)
        assertEquals(1L, removedTag)
    }

    @Test
    fun noFilterRowForTheDefaultView() {
        setContent(VaultListUiState(items = listOf(gmail), counts = populated))

        compose.onAllNodesWithTag(VaultListTags.CLEAR_FILTERS).assertCountEquals(0)
    }

    @Test
    fun favoriteToggleReflectsAndReportsState() {
        var toggled: Pair<String, Boolean>? = null
        setContent(
            VaultListUiState(items = listOf(gmail, visa), counts = populated),
            VaultListActions(onFavorite = { item, favorite -> toggled = item.uuid to favorite }),
        )

        compose.onNodeWithTag(VaultListTags.favorite("a")).assertIsOn()
        compose.onNodeWithTag(VaultListTags.favorite("b")).assertIsOff()
        compose.onNodeWithTag(VaultListTags.favorite("b")).performClick()

        assertEquals("b" to true, toggled)
    }

    @Test
    fun itemMenuOffersArchiveTagsAndTrash() {
        var trashed: String? = null
        var tagged: String? = null
        setContent(
            VaultListUiState(items = listOf(gmail), counts = populated),
            VaultListActions(onTrash = { trashed = it.uuid }, onEditTags = { tagged = it.uuid }),
        )

        compose.onNodeWithTag(VaultListTags.itemMenu("a")).performClick()
        compose.onNodeWithTag(VaultListTags.menu("archive")).assertIsDisplayed()
        compose.onNodeWithTag(VaultListTags.menu("tags")).performClick()
        compose.onNodeWithTag(VaultListTags.itemMenu("a")).performClick()
        compose.onNodeWithTag(VaultListTags.menu("trash")).performClick()

        assertEquals("a", tagged)
        assertEquals("a", trashed)
    }

    @Test
    fun archivedItemsOfferUnarchive() {
        setContent(
            VaultListUiState(
                filter = VaultFilter(smartList = SmartList.ARCHIVE),
                items = listOf(visa.copy(archived = true)),
                counts = populated,
            ),
        )

        compose.onNodeWithTag(VaultListTags.itemMenu("b")).performClick()

        compose.onNodeWithTag(VaultListTags.menu("unarchive")).assertIsDisplayed()
        compose.onAllNodesWithTag(VaultListTags.menu("archive")).assertCountEquals(0)
    }

    @Test
    fun trashedItemsCanOnlyBeRestoredOrPurged() {
        setContent(
            VaultListUiState(
                filter = VaultFilter(smartList = SmartList.TRASH),
                items = listOf(visa.copy(trashed = true)),
                counts = populated.copy(trash = 1),
            ),
        )

        // No favourite star on something in the trash.
        compose.onAllNodesWithTag(VaultListTags.favorite("b")).assertCountEquals(0)

        compose.onNodeWithTag(VaultListTags.itemMenu("b")).performClick()
        compose.onNodeWithTag(VaultListTags.menu("restore")).assertIsDisplayed()
        compose.onNodeWithTag(VaultListTags.menu("purge")).assertIsDisplayed()
        compose.onAllNodesWithTag(VaultListTags.menu("trash")).assertCountEquals(0)
        compose.onAllNodesWithTag(VaultListTags.menu("tags")).assertCountEquals(0)
    }

    @Test
    fun deletingForeverAsksFirst() {
        var purged: String? = null
        setContent(
            VaultListUiState(
                filter = VaultFilter(smartList = SmartList.TRASH),
                items = listOf(visa.copy(trashed = true)),
                counts = populated.copy(trash = 1),
            ),
            VaultListActions(onDeleteForever = { purged = it.uuid }),
        )

        compose.onNodeWithTag(VaultListTags.itemMenu("b")).performClick()
        compose.onNodeWithTag(VaultListTags.menu("purge")).performClick()

        assertNull("purged without confirmation", purged)
        compose.onNodeWithText("Delete “Visa” forever?").assertIsDisplayed()

        compose.onNodeWithTag(VaultListTags.CONFIRM).performClick()

        assertEquals("b", purged)
    }

    @Test
    fun emptyingTheTrashAsksFirst() {
        var emptied = false
        setContent(
            VaultListUiState(
                filter = VaultFilter(smartList = SmartList.TRASH),
                items = listOf(visa.copy(trashed = true)),
                counts = populated.copy(trash = 3),
            ),
            VaultListActions(onEmptyTrash = { emptied = true }),
        )

        compose.onNodeWithTag(VaultListTags.OVERFLOW).performClick()
        compose.onNodeWithTag(VaultListTags.menu("empty-trash")).performClick()

        assertEquals(false, emptied)
        compose.onNodeWithText("3 items will be deleted forever. This cannot be undone.").assertIsDisplayed()

        compose.onNodeWithTag(VaultListTags.CONFIRM).performClick()

        assertEquals(true, emptied)
    }

    @Test
    fun lockButtonLocks() {
        var locked = false
        setContent(VaultListUiState(items = listOf(gmail), counts = populated), VaultListActions(onLock = { locked = true }))

        compose.onNodeWithContentDescription("Lock vault").performClick()

        assertEquals(true, locked)
    }

    @Test
    fun drawerShowsListsTypesInUseAndTags() {
        compose.setContent {
            VaultDrawerContent(
                state = VaultListUiState(
                    filter = VaultFilter(smartList = SmartList.FAVORITES),
                    tags = listOf(work),
                    counts = populated.copy(byTag = mapOf(work.id to 1)),
                ),
                onSmartList = {},
                onTemplate = {},
                onTag = {},
                onManageTags = {},
            )
        }

        compose.onNodeWithTag(VaultListTags.smartList(SmartList.FAVORITES)).assertIsSelected()
        compose.onNodeWithTag(VaultListTags.template(Template.LOGIN)).assertIsDisplayed()
        // No wifi items, so no wifi row.
        compose.onAllNodesWithTag(VaultListTags.template(Template.WIFI)).assertCountEquals(0)
        compose.onNodeWithTag(VaultListTags.tag(work.id)).assertIsDisplayed()
        compose.onNodeWithText("Manage tags").assertIsDisplayed()
    }

    @Test
    fun drawerKeepsTheActiveTemplateEvenWhenEmpty() {
        compose.setContent {
            VaultDrawerContent(
                state = VaultListUiState(filter = VaultFilter(template = Template.WIFI)),
                onSmartList = {},
                onTemplate = {},
                onTag = {},
                onManageTags = {},
            )
        }

        compose.onNodeWithTag(VaultListTags.template(Template.WIFI)).assertIsSelected()
    }

    @Test
    fun auditListsAppearInTheDrawerOnlyWhenTheyHoldSomething() {
        compose.setContent {
            VaultDrawerContent(
                state = VaultListUiState(counts = populated.copy(weak = 2)),
                onSmartList = {},
                onTemplate = {},
                onTag = {},
                onManageTags = {},
            )
        }

        compose.onNodeWithTag(VaultListTags.smartList(SmartList.WEAK)).assertIsDisplayed()
        compose.onAllNodesWithTag(VaultListTags.smartList(SmartList.BREACHED)).assertCountEquals(0)
        compose.onAllNodesWithTag(VaultListTags.smartList(SmartList.REUSED)).assertCountEquals(0)
        compose.onNodeWithTag(VaultListTags.AUDIT).assertIsDisplayed()
    }

    @Test
    fun drawerReportsSelections() {
        var list: SmartList? = null
        var tag: Long? = null
        compose.setContent {
            VaultDrawerContent(
                state = VaultListUiState(tags = listOf(work), counts = populated),
                onSmartList = { list = it },
                onTemplate = {},
                onTag = { tag = it },
                onManageTags = {},
            )
        }

        compose.onNodeWithTag(VaultListTags.smartList(SmartList.TRASH)).performClick()
        compose.onNodeWithTag(VaultListTags.tag(work.id)).performClick()

        assertEquals(SmartList.TRASH, list)
        assertEquals(work.id, tag)
    }

    private fun setContent(state: VaultListUiState, actions: VaultListActions = VaultListActions()) {
        compose.setContent { VaultListScreen(state = state, actions = actions) }
    }
}
