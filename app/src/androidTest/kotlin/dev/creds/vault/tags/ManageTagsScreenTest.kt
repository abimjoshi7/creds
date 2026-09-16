package dev.creds.vault.tags

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.data.repository.TagResult
import dev.creds.vault.core.model.Tag
import dev.creds.vault.items.TagPickerDialog
import dev.creds.vault.items.TagPickerTags
import dev.creds.vault.ui.TagPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManageTagsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val work = Tag(1, "work")
    private val home = Tag(2, "home", color = TagPalette.colors[2])

    @Test
    fun explainsHavingNoTags() {
        setContent(ManageTagsUiState(tags = emptyList()))

        compose.onNodeWithTag(ManageTagsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("No tags yet").assertIsDisplayed()
    }

    @Test
    fun listsTagsWithTheirCounts() {
        setContent(ManageTagsUiState(tags = listOf(home, work), counts = mapOf(1L to 3, 2L to 1)))

        compose.onNodeWithText("work").assertIsDisplayed()
        compose.onNodeWithText("3 items").assertIsDisplayed()
        compose.onNodeWithText("1 item").assertIsDisplayed()
    }

    @Test
    fun tappingATagEditsIt() {
        var edited: Tag? = null
        setContent(ManageTagsUiState(tags = listOf(work)), onEdit = { edited = it })

        compose.onNodeWithTag(ManageTagsTags.row(work.id)).performClick()

        assertEquals(work, edited)
    }

    @Test
    fun deletingSaysTheItemsAreKept() {
        var deleted: Tag? = null
        setContent(
            ManageTagsUiState(tags = listOf(work), counts = mapOf(1L to 3)),
            onDelete = { deleted = it },
        )

        compose.onNodeWithTag(ManageTagsTags.delete(work.id)).performClick()

        assertNull("deleted without confirmation", deleted)
        compose.onNodeWithText("It will be removed from 3 items. The items themselves are kept.")
            .assertIsDisplayed()

        compose.onNodeWithText("Delete tag").performClick()

        assertEquals(work, deleted)
    }

    @Test
    fun editorCannotSaveABlankName() {
        setContent(
            ManageTagsUiState(tags = emptyList(), editor = TagEditorState(tagId = null, name = " # ", color = null)),
        )

        compose.onNodeWithText("Create").assertIsDisplayed()
        compose.onNodeWithTag(ManageTagsTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun editorShowsTheCurrentNameColourAndErrors() {
        setContent(
            ManageTagsUiState(
                tags = listOf(home),
                editor = TagEditorState(tagId = 2, name = "home", color = home.color, error = "“work” already exists"),
            ),
        )

        compose.onNodeWithText("Edit tag").assertIsDisplayed()
        compose.onNodeWithText("“work” already exists").assertIsDisplayed()
        compose.onNodeWithTag(ManageTagsTags.swatch(home.color)).assertIsSelected()
        compose.onNodeWithTag(ManageTagsTags.SAVE).assertIsEnabled()
    }

    @Test
    fun choosingAColourReportsIt() {
        var chosen: Int? = -1
        setContent(
            ManageTagsUiState(tags = emptyList(), editor = TagEditorState(tagId = null, name = "x", color = null)),
            onColorChange = { chosen = it },
        )

        compose.onNodeWithTag(ManageTagsTags.swatch(TagPalette.colors[0])).performClick()

        assertEquals(TagPalette.colors[0], chosen)
    }

    @Test
    fun pickerStartsWithTheItemsTagsAndSavesChanges() {
        var saved: Set<Long>? = null
        compose.setContent {
            TagPickerDialog(
                itemTitle = "Gmail",
                allTags = listOf(home, work),
                initiallySelected = setOf(work.id),
                onCreateTag = { _, _ -> },
                onConfirm = { saved = it },
                onDismiss = {},
            )
        }

        compose.onNodeWithTag(TagPickerTags.option(home.id)).performClick()
        compose.onNodeWithTag(TagPickerTags.option(work.id)).performClick()
        compose.onNodeWithTag(TagPickerTags.SAVE).performClick()

        assertEquals(setOf(home.id), saved)
    }

    @Test
    fun aTagCreatedInThePickerIsSelected() {
        var saved: Set<Long>? = null
        compose.setContent {
            TagPickerDialog(
                itemTitle = "Gmail",
                allTags = listOf(work),
                initiallySelected = emptySet(),
                onCreateTag = { _, onResult -> onResult(TagResult.Saved(Tag(9, "new"))) },
                onConfirm = { saved = it },
                onDismiss = {},
            )
        }

        compose.onNodeWithTag(TagPickerTags.NEW_NAME).performClick()
        compose.onNodeWithTag(TagPickerTags.NEW_NAME).performTextInput("new")
        compose.onNodeWithTag(TagPickerTags.ADD).performClick()
        compose.onNodeWithTag(TagPickerTags.SAVE).performClick()

        assertEquals(setOf(9L), saved)
    }

    @Test
    fun aDuplicateInThePickerExplainsItself() {
        compose.setContent {
            TagPickerDialog(
                itemTitle = "Gmail",
                allTags = listOf(work),
                initiallySelected = emptySet(),
                onCreateTag = { _, onResult -> onResult(TagResult.Duplicate("work")) },
                onConfirm = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithTag(TagPickerTags.NEW_NAME).performTextInput("WORK")
        compose.onNodeWithTag(TagPickerTags.ADD).performClick()

        compose.onNodeWithText("“work” already exists").assertIsDisplayed()
    }

    private fun setContent(
        state: ManageTagsUiState,
        onEdit: (Tag) -> Unit = {},
        onDelete: (Tag) -> Unit = {},
        onColorChange: (Int?) -> Unit = {},
    ) {
        compose.setContent {
            ManageTagsScreen(
                state = state,
                onBack = {},
                onAdd = {},
                onEdit = onEdit,
                onDelete = onDelete,
                onNameChange = {},
                onColorChange = onColorChange,
                onSave = {},
                onDismissEditor = {},
            )
        }
    }
}
