package dev.creds.vault.importer

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.creds.vault.core.domain.importer.ImportRow
import dev.creds.vault.core.domain.importer.ImportStatus
import dev.creds.vault.core.domain.importer.ImportWarnings
import dev.creds.vault.core.domain.importer.ImportedItem
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultItem
import org.junit.jupiter.api.Test

class ImportUiStateTest {

    private fun row(status: ImportStatus, selected: Boolean = status == ImportStatus.NEW) =
        ImportRow(ImportedItem(VaultItem(uuid = "u", template = Template.LOGIN, title = "t", createdAt = 0, updatedAt = 0)), status, selected)

    @Test
    fun `counts follow status and selection separately`() {
        val state = ImportUiState(
            rows = listOf(row(ImportStatus.NEW), row(ImportStatus.NEW, selected = false), row(ImportStatus.DUPLICATE, selected = true)),
        )

        assertThat(state.newCount).isEqualTo(2)
        assertThat(state.duplicateCount).isEqualTo(1)
        assertThat(state.selectedCount).isEqualTo(2)
    }

    @Test
    fun `warnings read as plain sentences, and nothing to report says nothing`() {
        assertThat(warningLines(ImportWarnings())).isEmpty()
        assertThat(
            warningLines(ImportWarnings(encryptedHistorySkipped = 2, attachmentsSkipped = 1, unknownFieldTypes = setOf("x", "y"))),
        ).containsExactly(
            "2 previous values Enpass kept encrypted can't be imported.",
            "1 attachments are not imported; Creds does not store files.",
            "Unfamiliar field types (x, y) are imported as text.",
        )
    }
}
