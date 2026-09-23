package dev.creds.vault.core.domain.importer

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import org.junit.jupiter.api.Test

/** The Enpass parser against invented exports. The real vault is checked by [EnpassBackupAcceptanceTest]. */
class EnpassParserTest {

    private val export = """
        {
          "folders": [{"uuid": "f1", "title": " Work "}, {"uuid": "f2", "title": ""}],
          "items": [
            {
              "uuid": "0b1c2d3e-0000-4000-8000-000000000001",
              "title": "Example Mail",
              "subtitle": "•••• leaked",
              "note": "line one\nline two",
              "category": "login",
              "template_type": "login.default",
              "favorite": 1, "archived": 0, "trashed": false,
              "createdAt": 1600000000, "updated_at": 1700000000,
              "folders": ["f1", "f2", "missing"],
              "icon": {"type": 1, "image": {"file": "x"}},
              "fields": [
                {"uid": 3, "type": "password", "label": "Password", "value": "sample-pass", "sensitive": 1, "order": 3,
                 "deleted": 0, "updated_at": 1700000000, "value_updated_at": 1650000000,
                 "history": [
                   {"value": "older", "updated_at": 1640000000, "encrypted": false},
                   {"value": "newer", "updated_at": 1645000000, "encrypted": false},
                   {"value": "opaque", "updated_at": 1646000000, "encrypted": true}
                 ]},
                {"uid": 1, "type": "username", "label": "Username", "value": "someone", "sensitive": 0, "order": 1,
                 "deleted": 0, "updated_at": 1, "value_updated_at": 1},
                {"uid": 2, "type": "section", "label": "More", "value": "stray", "sensitive": 0, "order": 2,
                 "deleted": 0, "updated_at": 1, "value_updated_at": 1},
                {"uid": 4, "type": "totp", "label": "TOTP", "value": "", "sensitive": 0, "order": 4,
                 "deleted": 0, "updated_at": 1, "value_updated_at": 1},
                {"uid": 5, "type": "hologram", "label": "Odd", "value": "x", "sensitive": 1, "order": 5,
                 "deleted": 0, "updated_at": 1, "value_updated_at": 1},
                {"uid": 6, "type": "text", "label": "Gone", "value": "x", "sensitive": 0, "order": 6,
                 "deleted": 1, "updated_at": 1, "value_updated_at": 1}
              ]
            },
            {
              "uuid": "not-a-uuid",
              "title": "",
              "category": "note",
              "template_type": "note.default",
              "createdAt": 1, "updated_at": 2,
              "attachments": [{"name": "a"}]
            },
            {"uuid": "0b1c2d3e-0000-4000-8000-000000000003", "title": "Thing", "category": "license", "template_type": "license.software"}
          ]
        }
    """.trimIndent()

    private val parsed = EnpassParser.parse(export, newUuid = { "generated" })

    @Test
    fun `fields keep Enpass order, and deleted ones are dropped and counted`() {
        val item = parsed.items[0].item

        assertThat(item.fields.map { it.type }).containsExactly(
            FieldType.USERNAME, FieldType.SECTION, FieldType.PASSWORD, FieldType.TOTP, FieldType.TEXT,
        )
        assertThat(item.fields.map { it.order }).containsExactly(0, 1, 2, 3, 4)
        assertThat(parsed.warnings.deletedFieldsSkipped).isEqualTo(1)
    }

    @Test
    fun `sensitivity is kept and raised, never lowered`() {
        val fields = parsed.items[0].item.fields.associateBy { it.label }

        assertThat(fields.getValue("Password").sensitive).isTrue()
        assertThat(fields.getValue("TOTP").sensitive).isTrue()
        assertThat(fields.getValue("Odd").sensitive).isTrue()
        assertThat(fields.getValue("Username").sensitive).isFalse()
    }

    @Test
    fun `timestamps become milliseconds and the value clock is kept`() {
        val item = parsed.items[0].item
        val password = item.fields.single { it.type == FieldType.PASSWORD }

        assertThat(item.createdAt).isEqualTo(1_600_000_000_000)
        assertThat(item.updatedAt).isEqualTo(1_700_000_000_000)
        assertThat(password.valueUpdatedAt).isEqualTo(1_650_000_000_000)
    }

    @Test
    fun `readable history comes newest first, encrypted history is counted`() {
        val history = parsed.items[0].history

        assertThat(history.keys).isEqualTo(setOf(2))
        assertThat(history.getValue(2).map { it.value to it.replacedAt })
            .containsExactly("newer" to 1_645_000_000_000, "older" to 1_640_000_000_000)
        assertThat(parsed.warnings.encryptedHistorySkipped).isEqualTo(1)
    }

    @Test
    fun `item flags, note, tags and a recomputed subtitle`() {
        val imported = parsed.items[0]

        assertThat(imported.item.favorite).isTrue()
        assertThat(imported.item.trashed).isFalse()
        assertThat(imported.item.note).isEqualTo("line one\nline two")
        assertThat(imported.tagNames).containsExactly("Work")
        // Never the exported subtitle: only non-sensitive fields may become one.
        assertThat(imported.item.subtitle).isEqualTo("someone")
        assertThat(imported.item.fields.single { it.type == FieldType.SECTION }.value).isEqualTo("")
    }

    @Test
    fun `a fieldless note with a bad uuid still imports`() {
        val note = parsed.items[1].item

        assertThat(note.uuid).isEqualTo("generated")
        assertThat(note.template).isEqualTo(Template.NOTE)
        assertThat(note.title).isEqualTo("Secure note")
        assertThat(note.fields).isEqualTo(emptyList())
        assertThat(parsed.warnings.attachmentsSkipped).isEqualTo(1)
    }

    @Test
    fun `unknown types and templates are reported, not lost`() {
        assertThat(parsed.warnings.unknownFieldTypes).isEqualTo(setOf("hologram"))
        assertThat(parsed.warnings.unmappedTemplates).isEqualTo(setOf("license.software"))
        assertThat(parsed.items[2].item.template).isEqualTo(Template.MISC)
    }

    @Test
    fun `template mapping`() {
        assertThat(EnpassParser.templateFor("finance", "finance.bankaccount")).isEqualTo(Template.BANK_ACCOUNT to true)
        assertThat(EnpassParser.templateFor("creditcard", "creditcard.default")).isEqualTo(Template.CARD to true)
        assertThat(EnpassParser.templateFor("computer", "computer.wifi")).isEqualTo(Template.WIFI to true)
        assertThat(EnpassParser.templateFor("travel", "travel.passport")).isEqualTo(Template.PASSPORT to true)
        assertThat(EnpassParser.templateFor("finance", "finance.other")).isEqualTo(Template.MISC to true)
    }

    @Test
    fun `malformed input is refused without quoting it`() {
        assertThat(runCatching { EnpassParser.parse("{\"items\": [ {\"title\": \"secret-title\", ") })
            .isFailure()
            .isInstanceOf(ImportException::class)
            .prop(Throwable::message)
            .isEqualTo("This is not an Enpass JSON export.")
    }

    @Test
    fun `duplicates match on title and username, including within the file`() {
        val rows = ImportPlanner.plan(
            EnpassParser.parse(
                """{"items": [
                    {"uuid": "0b1c2d3e-0000-4000-8000-00000000000a", "title": " example mail ", "category": "login",
                     "fields": [{"type": "email", "value": "SOMEONE", "order": 1}]},
                    {"uuid": "0b1c2d3e-0000-4000-8000-00000000000b", "title": "Other", "category": "login"},
                    {"uuid": "0b1c2d3e-0000-4000-8000-00000000000b", "title": "Other", "category": "login"}
                ]}""",
            ),
            existing = listOf(ExistingItemKey("0b1c2d3e-0000-4000-8000-00000000000a", "Example Mail", "someone")),
            newUuid = { "fresh" },
        )

        assertThat(rows.map { it.status }).containsExactly(ImportStatus.DUPLICATE, ImportStatus.NEW, ImportStatus.DUPLICATE)
        assertThat(rows.map { it.selected }).containsExactly(false, true, false)
        // A taken uuid is replaced, so an import can never overwrite an item.
        assertThat(rows.map { it.imported.item.uuid }).containsExactly("fresh", "0b1c2d3e-0000-4000-8000-00000000000b", "fresh")
    }
}
