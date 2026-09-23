package dev.creds.vault.items

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.model.FieldHistoryEntry
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The read-only item view, rendered in-process.
 *
 * What matters most here is what is *not* on screen: a secret must stay masked until the
 * user asks for it, and must not leak its length through the mask.
 */
@RunWith(AndroidJUnit4::class)
class ItemDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val login = VaultItem(
        uuid = "a",
        template = Template.LOGIN,
        title = "Gmail",
        note = "backup codes in the safe",
        createdAt = 0,
        updatedAt = 0,
        fields = listOf(
            VaultField(uid = 1, type = FieldType.USERNAME, label = "Username", value = "alice", order = 0),
            VaultField(uid = 2, type = FieldType.PASSWORD, label = "Password", value = "correct-horse", order = 1),
            VaultField(uid = 3, type = FieldType.EMAIL, label = "Email", value = "", order = 2),
            VaultField(uid = 4, type = FieldType.TOTP, label = "One-time code", value = "JBSWY3DPEHPK3PXP", order = 3),
        ),
    )

    @Test
    fun secretsStartMaskedAtAFixedWidthAndPlainValuesDoNot() {
        setContent(ItemDetailUiState(loading = false, item = login))

        compose.onNodeWithTag(ItemDetailTags.value(1)).assertTextEquals("alice")
        compose.onNodeWithTag(ItemDetailTags.value(2)).assertTextEquals(MASK)
        compose.onAllNodesWithText("correct-horse").assertCountEquals(0)
        compose.onAllNodesWithTag(ItemDetailTags.reveal(1)).assertCountEquals(0)
    }

    @Test
    fun revealTogglesASecret() {
        setContent(ItemDetailUiState(loading = false, item = login))

        compose.onNodeWithTag(ItemDetailTags.reveal(2)).performClick()
        compose.onNodeWithTag(ItemDetailTags.value(2)).assertTextEquals("correct-horse")

        compose.onNodeWithTag(ItemDetailTags.reveal(2)).performClick()
        compose.onNodeWithTag(ItemDetailTags.value(2)).assertTextEquals(MASK)
    }

    @Test
    fun copyingAMaskedSecretCopiesTheRealValue() {
        var copied: Pair<String, String>? = null
        setContent(
            ItemDetailUiState(loading = false, item = login),
            ItemDetailActions(onCopy = { label, value -> copied = label to value }),
        )

        compose.onNodeWithTag(ItemDetailTags.copy(2)).performClick()

        assertEquals("Password" to "correct-horse", copied)
    }

    @Test
    fun emptyFieldsAreHiddenAndTotpShowsACodeNotTheSecret() {
        setContent(ItemDetailUiState(loading = false, item = login))

        compose.onAllNodesWithTag(ItemDetailTags.value(3)).assertCountEquals(0)
        compose.onAllNodesWithTag(ItemDetailTags.value(4)).assertCountEquals(0)
        compose.onAllNodesWithText("JBSWY3DPEHPK3PXP").assertCountEquals(0)
        compose.onNodeWithTag(TotpTags.CODE).assertIsDisplayed()
    }

    @Test
    fun historyLinkAppearsOnlyWithHistoryAndOpensIt() {
        var opened: VaultField? = null
        setContent(
            ItemDetailUiState(loading = false, item = login, historyCounts = mapOf(2L to 3)),
            ItemDetailActions(onOpenHistory = { opened = it }),
        )

        compose.onAllNodesWithTag(ItemDetailTags.history(1)).assertCountEquals(0)
        compose.onNodeWithText("3 previous values").performClick()

        assertEquals(2L, opened?.uid)
    }

    @Test
    fun historyEntriesStartMasked() {
        val history = FieldHistoryState(
            fieldUid = 2,
            fieldLabel = "Password",
            loading = false,
            entries = listOf(FieldHistoryEntry(id = 9, value = "old-horse", replacedAt = 0)),
        )
        setContent(ItemDetailUiState(loading = false, item = login, history = history))

        compose.onNodeWithTag(FieldHistoryTags.value(9)).assertTextEquals(MASK)
        compose.onNodeWithTag(FieldHistoryTags.reveal(9)).performClick()
        compose.onNodeWithTag(FieldHistoryTags.value(9)).assertTextEquals("old-horse")
    }

    @Test
    fun aTrashedItemOffersRestoreAndNoEditing() {
        var restored = false
        setContent(
            ItemDetailUiState(loading = false, item = login.copy(trashed = true)),
            ItemDetailActions(onRestore = { restored = true }),
        )

        compose.onNodeWithTag(ItemDetailTags.TRASH_BANNER).assertIsDisplayed()
        compose.onAllNodesWithTag(ItemDetailTags.EDIT).assertCountEquals(0)
        compose.onAllNodesWithTag(ItemDetailTags.FAVORITE).assertCountEquals(0)
        compose.onNodeWithTag(ItemDetailTags.MENU).performClick()
        compose.onNodeWithTag(ItemDetailTags.menu("restore")).performClick()

        assertEquals(true, restored)
    }

    @Test
    fun deletingForeverAsksFirst() {
        var deleted = false
        setContent(
            ItemDetailUiState(loading = false, item = login.copy(trashed = true)),
            ItemDetailActions(onDeleteForever = { deleted = true }),
        )

        compose.onNodeWithTag(ItemDetailTags.MENU).performClick()
        compose.onNodeWithTag(ItemDetailTags.menu("purge")).performClick()
        assertEquals(false, deleted)

        compose.onNodeWithTag(VaultListTags.CONFIRM).performClick()
        assertEquals(true, deleted)
    }

    @Test
    fun menuActionsReport() {
        var archived: Boolean? = null
        var trashed = false
        var edited = false
        setContent(
            ItemDetailUiState(loading = false, item = login),
            ItemDetailActions(onArchive = { archived = it }, onTrash = { trashed = true }, onEdit = { edited = true }),
        )

        compose.onNodeWithTag(ItemDetailTags.EDIT).performClick()
        compose.onNodeWithTag(ItemDetailTags.MENU).performClick()
        compose.onNodeWithTag(ItemDetailTags.menu("archive")).performClick()
        compose.onNodeWithTag(ItemDetailTags.MENU).performClick()
        compose.onNodeWithTag(ItemDetailTags.menu("trash")).performClick()

        assertEquals(true, edited)
        assertEquals(true, archived)
        assertEquals(true, trashed)
    }

    @Test
    fun theNoteIsShownAndCopyable() {
        var copied: String? = null
        setContent(
            ItemDetailUiState(loading = false, item = login),
            ItemDetailActions(onCopy = { _, value -> copied = value }),
        )

        compose.onNodeWithTag(ItemDetailTags.CONTENT).performScrollToNode(hasTestTag(ItemDetailTags.NOTE))
        compose.onNodeWithTag(ItemDetailTags.NOTE).assertTextEquals("backup codes in the safe")
        compose.onNodeWithTag(ItemDetailTags.NOTE_COPY).performClick()

        assertEquals("backup codes in the safe", copied)
    }

    @Test
    fun aMissingItemSaysSo() {
        setContent(ItemDetailUiState(loading = false, missing = true))

        compose.onNodeWithTag(ItemEditorTags.MISSING).assertIsDisplayed()
    }

    private fun setContent(state: ItemDetailUiState, actions: ItemDetailActions = ItemDetailActions()) {
        compose.setContent { ItemDetailScreen(state = state, actions = actions) }
    }
}
