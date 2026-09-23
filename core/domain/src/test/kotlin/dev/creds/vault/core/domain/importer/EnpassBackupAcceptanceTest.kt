package dev.creds.vault.core.domain.importer

import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The v1 import acceptance test: the real `backup.json` at the repository root (spec §11).
 *
 * That file is live credential data, so this test reads it in place, asserts only on
 * counts and types, and never puts a title, label or value into an assertion message. It
 * is skipped wherever the file is absent — it is gitignored and exists on one machine.
 */
class EnpassBackupAcceptanceTest {

    private val file: File? = System.getProperty("creds.backupJson")?.let(::File)?.takeIf(File::isFile)

    @Test
    fun `the real vault imports losslessly`() {
        assumeTrue(file != null, "backup.json is not present")
        val parsed = EnpassParser.parse(file!!.readText(Charsets.UTF_8))
        val items = parsed.items.map { it.item }
        val fields = items.flatMap { it.fields }

        assertEquals(16, items.size, "item count")
        // Enpass's five categories: login, finance (bank accounts and "other"), note, misc
        // and computer. The three "other" templates are all Creds' "Other".
        assertEquals(
            mapOf(Template.LOGIN to 9, Template.BANK_ACCOUNT to 3, Template.NOTE to 1, Template.MISC to 3),
            items.groupingBy { it.template }.eachCount(),
            "items per template",
        )
        assertEquals(179, fields.size, "total fields")
        assertEquals(45, fields.count { it.type == FieldType.TEXT }, "text fields")
        assertEquals(21, fields.count { it.type == FieldType.SECTION }, "section fields")
        assertEquals(9, fields.count { it.type == FieldType.TOTP }, "TOTP fields")
        assertEquals(3, fields.count { it.type == FieldType.CARD_NUMBER }, "card number fields")
        assertEquals(38, parsed.items.sumOf { item -> item.history.values.sumOf { it.size } }, "history entries")

        assertTrue(parsed.warnings.isEmpty, "the import reported something it could not carry over")
        assertTrue(fields.filter { it.type.defaultSensitive }.all { it.sensitive }, "secret types stay sensitive")
        assertTrue(items.all { it.createdAt > 1_000_000_000_000 }, "timestamps in milliseconds")
        assertTrue(items.all { item -> item.fields.map { it.order } == item.fields.indices.toList() }, "field order contiguous")
    }

    @Test
    fun `importing the real vault twice finds every item a duplicate`() {
        assumeTrue(file != null, "backup.json is not present")
        val parsed = EnpassParser.parse(file!!.readText(Charsets.UTF_8))
        val existing = parsed.items.map { ExistingItemKey(it.item.uuid, it.item.title, ImportPlanner.usernameOf(it.item)) }

        val rows = ImportPlanner.plan(parsed, existing, newUuid = { java.util.UUID.randomUUID().toString() })

        assertTrue(rows.all { it.status == ImportStatus.DUPLICATE && !it.selected }, "a re-import selected something")
        assertEquals(16, rows.map { it.imported.item.uuid }.toSet().size, "uuids stay unique")
    }
}
