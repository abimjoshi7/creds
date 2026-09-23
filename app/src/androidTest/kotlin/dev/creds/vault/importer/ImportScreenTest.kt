package dev.creds.vault.importer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.domain.importer.ImportRow
import dev.creds.vault.core.domain.importer.ImportStatus
import dev.creds.vault.core.domain.importer.ImportWarnings
import dev.creds.vault.core.domain.importer.ImportedItem
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The import preview, rendered in-process against invented rows. */
@RunWith(AndroidJUnit4::class)
class ImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(title: String, status: ImportStatus) = ImportRow(
        ImportedItem(
            VaultItem(
                uuid = title, template = Template.LOGIN, title = title, createdAt = 0, updatedAt = 0,
                fields = listOf(VaultField(1, FieldType.USERNAME, "Username", "someone")),
            ),
        ),
        status,
    )

    private val preview = ImportUiState(
        stage = ImportStage.PREVIEW,
        rows = listOf(row("New one", ImportStatus.NEW), row("Old one", ImportStatus.DUPLICATE)),
    )

    @Test
    fun previewSummarisesAndPreselectsOnlyNewItems() {
        setContent(preview)

        compose.onNodeWithTag(ImportTags.SUMMARY).assertTextEquals("1 new item · 1 already in your vault")
        compose.onNodeWithTag(ImportTags.row(0)).assertIsOn()
        compose.onNodeWithTag(ImportTags.row(1)).assertIsOff()
        compose.onNodeWithTag(ImportTags.COMMIT).assert(hasText("Import 1 item"))
        compose.onAllNodesWithTag(ImportTags.WARNINGS).assertCountEquals(0)
    }

    @Test
    fun togglingAndCommittingReport() {
        var toggled: Int? = null
        var committed = false
        setContent(preview, ImportActions(onToggle = { toggled = it }, onCommit = { committed = true }))

        compose.onNodeWithTag(ImportTags.row(1)).performClick()
        compose.onNodeWithTag(ImportTags.COMMIT).performClick()

        assertEquals(1, toggled)
        assertEquals(true, committed)
    }

    @Test
    fun nothingSelectedMeansNothingToImport() {
        setContent(preview.copy(rows = preview.rows.map { it.copy(selected = false) }))

        compose.onNodeWithTag(ImportTags.COMMIT).assertIsNotEnabled()
    }

    @Test
    fun warningsAreShownBeforeImporting() {
        setContent(preview.copy(warnings = ImportWarnings(attachmentsSkipped = 3)))

        compose.onNodeWithTag(ImportTags.WARNINGS).assertIsDisplayed()
    }

    @Test
    fun errorsAppearOnTheChooseStep() {
        var chose = false
        setContent(ImportUiState(error = "This is not an Enpass JSON export."), ImportActions(onChoose = { chose = true }))

        compose.onNodeWithTag(ImportTags.ERROR).assertTextEquals("This is not an Enpass JSON export.")
        compose.onNodeWithTag(ImportTags.CHOOSE).performClick()
        assertEquals(true, chose)
    }

    @Test
    fun doneRemindsToDeleteTheExport() {
        setContent(ImportUiState(stage = ImportStage.DONE, imported = 16))

        compose.onNodeWithTag(ImportTags.DONE).assertIsDisplayed()
        compose.onNode(hasText("Imported 16 items.")).assertIsDisplayed()
        compose.onNode(hasText("Delete the Enpass export file now", substring = true)).assertIsDisplayed()
    }

    private fun setContent(state: ImportUiState, actions: ImportActions = ImportActions()) {
        compose.setContent { ImportScreen(state = state, actions = actions) }
    }
}
