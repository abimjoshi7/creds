package dev.creds.vault.items

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.VaultField
import org.junit.jupiter.api.Test

class ItemDraftTest {

    private val username = field(uid = 1, type = FieldType.USERNAME, label = "Username", value = "alice")
    private val password = field(uid = 2, type = FieldType.PASSWORD, label = "Password", value = "old", sensitive = true)

    @Test
    fun `only a changed value moves valueUpdatedAt`() {
        val baseline = listOf(username, password)
        val edited = listOf(username.copy(label = "Login"), password.copy(value = "new"))

        val saved = edited.toVaultFields(baseline, now = 99)

        assertThat(saved.map { it.valueUpdatedAt }).containsExactly(10L, 99L)
        assertThat(saved.map { it.updatedAt }).containsExactly(99L, 99L)
    }

    @Test
    fun `order follows the draft, not the stored order`() {
        val saved = listOf(password, username).toVaultFields(listOf(username, password), now = 5)

        assertThat(saved.map { it.uid to it.order }).containsExactly(2L to 0, 1L to 1)
    }

    @Test
    fun `an added field counts as changed and a blank label falls back to its kind`() {
        val added = CustomFieldKind.HIDDEN.newField(key = "added:0", label = "  ", now = 1)
            .copy(value = "secret")

        val saved = listOf(added).toVaultFields(baseline = emptyList(), now = 50).single()

        assertThat(saved.uid).isEqualTo(0L)
        assertThat(saved.label).isEqualTo("Hidden text")
        assertThat(saved.valueUpdatedAt).isEqualTo(50L)
        assertThat(saved.sensitive).isTrue()
    }

    @Test
    fun `a section heading never carries a value`() {
        val section = field(uid = 3, type = FieldType.SECTION, label = "Recovery", value = "stray")

        assertThat(listOf(section).toVaultFields(listOf(section), now = 1).single().value).isEqualTo("")
    }

    @Test
    fun `no custom kind is weaker than its type`() {
        CustomFieldKind.entries.forEach { kind ->
            if (kind.type.defaultSensitive) assertThat(kind.sensitive).isTrue()
        }
        assertThat(CustomFieldKind.HIDDEN.sensitive).isTrue()
        assertThat(CustomFieldKind.TEXT.sensitive).isFalse()
    }

    @Test
    fun `the draft is dirty only when something the user controls changed`() {
        val loaded = ItemEditorUiState(title = "Mail", fields = listOf(username, password)).withBaseline()

        assertThat(loaded.isDirty).isFalse()
        assertThat(loaded.copy(busy = true, error = "x").isDirty).isFalse()
        assertThat(loaded.copy(title = "Mail 2").isDirty).isTrue()
        assertThat(loaded.copy(favorite = true).isDirty).isTrue()
        assertThat(loaded.copy(fields = listOf(username)).isDirty).isTrue()
        assertThat(loaded.copy(fields = listOf(username, password.copy(value = "new"))).isDirty).isTrue()
    }

    @Test
    fun `a state without a baseline is never dirty`() {
        assertThat(ItemEditorUiState(loading = true, title = "x").isDirty).isFalse()
    }

    private fun field(
        uid: Long,
        type: FieldType,
        label: String,
        value: String,
        sensitive: Boolean = type.defaultSensitive,
    ) = EditableField.from(
        VaultField(
            uid = uid,
            type = type,
            label = label,
            value = value,
            sensitive = sensitive,
            updatedAt = 10,
            valueUpdatedAt = 10,
        ),
    )
}
