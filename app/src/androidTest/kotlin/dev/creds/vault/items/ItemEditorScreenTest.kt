package dev.creds.vault.items

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The item editor, rendered in-process against hand-built state. */
@RunWith(AndroidJUnit4::class)
class ItemEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val username = EditableField.from(
        VaultField(uid = 1, type = FieldType.USERNAME, label = "Username", value = "alice"),
    )
    private val password = EditableField.from(
        VaultField(uid = 2, type = FieldType.PASSWORD, label = "Password", value = "correct-horse"),
    )
    private val section = EditableField.from(
        VaultField(uid = 3, type = FieldType.SECTION, label = "Recovery", value = ""),
    )
    private val loaded = ItemEditorUiState(
        template = Template.LOGIN,
        title = "Gmail",
        fields = listOf(username, section, password),
    ).withBaseline()

    @Test
    fun sectionHeadingsAreShownAsHeadingsNotInputs() {
        setContent(loaded)

        compose.onNodeWithTag(ItemEditorTags.field(section.key)).assertIsDisplayed()
        compose.onNodeWithText("Recovery").assertIsDisplayed()
    }

    @Test
    fun secretsAreObscuredUntilRevealed() {
        setContent(loaded)

        compose.onAllNodes(drawsText("correct-horse")).assertCountEquals(0)
        compose.onNodeWithTag(ItemEditorTags.reveal(password.key)).performClick()
        compose.onNode(drawsText("correct-horse")).assertIsDisplayed()
        compose.onAllNodesWithTag(ItemEditorTags.reveal(username.key)).assertCountEquals(0)
    }

    @Test
    fun removingAFieldReportsItsKey() {
        var removed: String? = null
        setContent(loaded, ItemEditorActions(onRemoveField = { removed = it }))

        compose.onNodeWithTag(ItemEditorTags.remove(password.key)).performClick()

        assertEquals(password.key, removed)
    }

    @Test
    fun addingAFieldReportsKindAndLabel() {
        var added: Pair<CustomFieldKind, String>? = null
        setContent(loaded, ItemEditorActions(onAddField = { kind, label -> added = kind to label }))

        compose.onNodeWithTag(ItemEditorTags.ADD_FIELD).performClick()
        compose.onNodeWithTag(ItemEditorTags.kind(CustomFieldKind.HIDDEN)).performClick()
        compose.onNodeWithTag(ItemEditorTags.ADD_FIELD_LABEL).performTextInput("Recovery key")
        compose.onNodeWithTag(ItemEditorTags.ADD_FIELD_CONFIRM).performClick()

        assertEquals(CustomFieldKind.HIDDEN to "Recovery key", added)
    }

    @Test
    fun leavingACleanDraftDoesNotAsk() {
        var left = false
        setContent(loaded, ItemEditorActions(onBack = { left = true }))

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(true, left)
    }

    @Test
    fun leavingADirtyDraftAsksFirst() {
        var left = false
        setContent(loaded.copy(title = "Gmail (work)"), ItemEditorActions(onBack = { left = true }))

        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(false, left)
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()

        compose.onNodeWithTag(ItemEditorTags.DISCARD).performClick()
        assertEquals(true, left)
    }

    @Test
    fun keepEditingStays() {
        var left = false
        setContent(loaded.copy(title = "Gmail (work)"), ItemEditorActions(onBack = { left = true }))

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Keep editing").performClick()

        assertEquals(false, left)
        compose.onAllNodesWithText("Discard changes?").assertCountEquals(0)
    }

    @Test
    fun onlyPasswordFieldsOfferTheGenerator() {
        var generating: String? = null
        setContent(loaded, ItemEditorActions(onGenerate = { generating = it }))

        compose.onAllNodesWithTag(ItemEditorTags.generate(username.key)).assertCountEquals(0)
        compose.onNodeWithTag(ItemEditorTags.generate(password.key)).performClick()

        assertEquals(password.key, generating)
    }

    @Test
    fun saveNeedsATitle() {
        setContent(loaded.copy(title = " "))

        compose.onNodeWithTag(ItemEditorTags.SAVE).assertIsNotEnabled()
    }

    private fun setContent(state: ItemEditorUiState, actions: ItemEditorActions = ItemEditorActions()) {
        compose.setContent { ItemEditorScreen(state = state, actions = actions) }
    }
}

/**
 * Text as drawn: a label, or a text field's visible (transformed) value. Unlike
 * `hasText`, it ignores a field's raw `InputText`, which Compose exposes even while
 * the field shows only bullets.
 */
private fun drawsText(value: String) = SemanticsMatcher("draws \"$value\"") { node ->
    node.config.getOrNull(SemanticsProperties.EditableText)?.text == value ||
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }
}
