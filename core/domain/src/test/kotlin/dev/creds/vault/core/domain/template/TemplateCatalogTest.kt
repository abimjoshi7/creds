package dev.creds.vault.core.domain.template

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import org.junit.jupiter.api.Test

class TemplateCatalogTest {

    @Test
    fun `every template has a spec and a name`() {
        Template.entries.forEach { template ->
            assertThat(TemplateCatalog.spec(template).template).isEqualTo(template)
            assertThat(TemplateCatalog.displayName(template).isNotBlank()).isTrue()
        }
    }

    @Test
    fun `no template declares a secret field as non-sensitive`() {
        // The FieldSpec constructor already refuses this; walking the table proves every
        // entry actually went through it.
        Template.entries.flatMap { TemplateCatalog.spec(it).fields }.forEach { spec ->
            if (spec.type.defaultSensitive) assertThat(spec.sensitive).isTrue()
        }
    }

    @Test
    fun `a spec cannot weaken a sensitive type`() {
        assertThat(runCatching { FieldSpec(FieldType.PASSWORD, "pw", sensitive = false) })
            .isFailure()
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `new fields carry the spec order, labels and sensitivity`() {
        val fields = TemplateCatalog.newFields(Template.BANK_ACCOUNT, now = 7L)

        assertThat(fields.map { it.order }).isEqualTo(fields.indices.toList())
        assertThat(fields.first { it.label == "Account number" }.sensitive).isTrue()
        fields.forEach {
            assertThat(it.uid).isEqualTo(0L)
            assertThat(it.value).isEmpty()
            assertThat(it.updatedAt).isEqualTo(7L)
        }
    }

    @Test
    fun `a note starts with no fields`() {
        assertThat(TemplateCatalog.newFields(Template.NOTE, now = 0)).isEmpty()
    }

    @Test
    fun `login subtitle prefers the username`() {
        val fields = listOf(
            field(FieldType.URL, "https://bank.com", order = 0),
            field(FieldType.EMAIL, "a@b.com", order = 1),
            field(FieldType.USERNAME, "alice", order = 2),
        )

        assertThat(TemplateCatalog.subtitleFor(Template.LOGIN, fields)).isEqualTo("alice")
    }

    @Test
    fun `login subtitle falls back when the username is blank`() {
        val fields = listOf(
            field(FieldType.USERNAME, "  ", order = 0),
            field(FieldType.EMAIL, "a@b.com", order = 1),
        )

        assertThat(TemplateCatalog.subtitleFor(Template.LOGIN, fields)).isEqualTo("a@b.com")
    }

    @Test
    fun `a sensitive value never becomes the subtitle`() {
        // A TEXT field marked secret — an account number — must not surface just because
        // TEXT is an eligible subtitle type for bank accounts.
        val fields = listOf(
            field(FieldType.TEXT, "12345678", order = 0, sensitive = true),
            field(FieldType.PASSWORD, "hunter2", order = 1, sensitive = true),
        )

        assertThat(TemplateCatalog.subtitleFor(Template.BANK_ACCOUNT, fields)).isEmpty()
    }

    @Test
    fun `a card subtitle never uses the card number`() {
        val fields = listOf(
            field(FieldType.CARD_NUMBER, "4111111111111111", order = 0, sensitive = true),
            field(FieldType.CARD_HOLDER, "Alice Smith", order = 1),
        )

        assertThat(TemplateCatalog.subtitleFor(Template.CARD, fields)).isEqualTo("Alice Smith")
    }

    @Test
    fun `deleted fields are ignored`() {
        val fields = listOf(
            field(FieldType.USERNAME, "old", order = 0, deleted = true),
            field(FieldType.USERNAME, "new", order = 1),
        )

        assertThat(TemplateCatalog.subtitleFor(Template.LOGIN, fields)).isEqualTo("new")
    }

    @Test
    fun `only the first line of a multi-line value is used`() {
        val fields = listOf(field(FieldType.TEXT, "Home Wi-Fi\nupstairs", order = 0))

        assertThat(TemplateCatalog.subtitleFor(Template.WIFI, fields)).isEqualTo("Home Wi-Fi")
    }

    @Test
    fun `field order decides between two of the same type`() {
        val fields = listOf(
            field(FieldType.TEXT, "second", order = 5),
            field(FieldType.TEXT, "first", order = 1),
        )

        assertThat(TemplateCatalog.subtitleFor(Template.MISC, fields)).isEqualTo("first")
    }

    @Test
    fun `template names are distinct`() {
        val names = Template.entries.map(TemplateCatalog::displayName)
        assertThat(names.distinct()).containsExactly(*names.toTypedArray())
    }

    private fun field(
        type: FieldType,
        value: String,
        order: Int,
        sensitive: Boolean = type.defaultSensitive,
        deleted: Boolean = false,
    ) = VaultField(
        uid = order.toLong() + 1,
        type = type,
        label = type.id,
        value = value,
        sensitive = sensitive,
        order = order,
        deleted = deleted,
    )
}
