package dev.creds.vault.items

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import org.junit.jupiter.api.Test

class ItemDetailRowsTest {

    @Test
    fun `empty template slots are hidden`() {
        val rows = item(
            field(1, FieldType.USERNAME, "alice"),
            field(2, FieldType.EMAIL, ""),
            field(3, FieldType.PASSWORD, "   "),
        ).detailRows()

        assertThat(rows.map { it.key }).containsExactly("field:1")
    }

    @Test
    fun `a heading shows only above something visible`() {
        val rows = item(
            field(1, FieldType.SECTION, "", label = "Empty section"),
            field(2, FieldType.TEXT, ""),
            field(3, FieldType.SECTION, "", label = "Recovery"),
            field(4, FieldType.TEXT, "code"),
            field(5, FieldType.SECTION, "", label = "Trailing"),
        ).detailRows()

        assertThat(rows.map { it::class to it.key }).containsExactly(
            DetailRow.Section::class to "section:3:2",
            DetailRow.Value::class to "field:4",
        )
    }

    @Test
    fun `a parseable TOTP secret becomes a code and an unparseable one stays a value`() {
        val rows = item(
            field(1, FieldType.TOTP, "JBSWY3DPEHPK3PXP"),
            field(2, FieldType.TOTP, "not base32 !!"),
        ).detailRows()

        assertThat(rows.map { it::class }).containsExactly(DetailRow.OneTimeCode::class, DetailRow.Value::class)
    }

    @Test
    fun `rows follow field order and skip tombstones`() {
        val rows = item(
            field(1, FieldType.TEXT, "b", order = 1),
            field(2, FieldType.TEXT, "a", order = 0),
            field(3, FieldType.TEXT, "gone", order = 2).copy(deleted = true),
        ).detailRows()

        assertThat(rows.map { it.key }).containsExactly("field:2", "field:1")
    }

    @Test
    fun `a note-only item has no field rows`() {
        assertThat(VaultItem(uuid = "n", template = Template.NOTE, title = "n", note = "x", createdAt = 0, updatedAt = 0).detailRows())
            .isEmpty()
    }

    private fun item(vararg fields: VaultField) = VaultItem(
        uuid = "u",
        template = Template.MISC,
        title = "Item",
        createdAt = 0,
        updatedAt = 0,
        fields = fields.mapIndexed { index, f -> if (f.order == -1) f.copy(order = index) else f },
    )

    private fun field(uid: Long, type: FieldType, value: String, label: String = type.id, order: Int = -1) =
        VaultField(uid = uid, type = type, label = label, value = value, order = order)
}
