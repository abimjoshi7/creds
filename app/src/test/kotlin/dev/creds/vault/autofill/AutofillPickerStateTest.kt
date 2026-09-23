package dev.creds.vault.autofill

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.creds.vault.core.model.Template
import org.junit.jupiter.api.Test

class AutofillPickerStateTest {

    private fun row(uuid: String, title: String, subtitle: String, verdict: PickVerdict = PickVerdict.UNCONFIRMED) =
        PickerRow(uuid, title, subtitle, Template.LOGIN, verdict)

    @Test
    fun `search matches title or subtitle, ignoring case and surrounding space`() {
        val state = AutofillPickerUiState(
            rows = listOf(row("a", "Gmail", "alice@example.com"), row("b", "Bank", "bob")),
            query = "  ALICE ",
        )

        assertThat(state.visibleRows.map { it.uuid }).containsExactly("a")
        assertThat(state.copy(query = "").visibleRows.map { it.uuid }).containsExactly("a", "b")
    }

    @Test
    fun `verdicts sort trusted first and refused last`() {
        assertThat(PickVerdict.entries.sortedBy { it.sortOrder })
            .containsExactly(PickVerdict.TRUSTED, PickVerdict.NOT_NEEDED, PickVerdict.UNCONFIRMED, PickVerdict.CONFLICT)
    }

    @Test
    fun `fingerprints show the first key's leading bytes in groups`() {
        val key = "0123456789ABCDEF".repeat(4) + ",FFFF"

        assertThat(AutofillViewModel.fingerprint(key)).isEqualTo("0123 4567 89AB CDEF 0123 4567 89AB CDEF…")
    }
}
