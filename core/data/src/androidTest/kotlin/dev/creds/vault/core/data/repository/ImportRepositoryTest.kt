package dev.creds.vault.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.domain.importer.EnpassParser
import dev.creds.vault.core.domain.importer.ImportPlanner
import dev.creds.vault.core.domain.importer.ImportStatus
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.VaultFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Writing an Enpass import into SQLCipher on a device, from an invented export. */
@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 13 })
    private lateinit var database: CredsDatabase
    private lateinit var repository: VaultRepository

    private val export = """
        {"folders": [{"uuid": "f", "title": "work"}],
         "items": [
          {"uuid": "0b1c2d3e-0000-4000-8000-0000000000d1", "title": "Example Mail", "category": "login",
           "template_type": "login.default", "createdAt": 1600000000, "updated_at": 1700000000, "folders": ["f"],
           "note": "imported note",
           "fields": [
             {"type": "username", "label": "Username", "value": "someone", "order": 1},
             {"type": "password", "label": "Password", "value": "current-Pass1", "sensitive": 1, "order": 2,
              "history": [{"value": "previous-Pass0", "updated_at": 1650000000, "encrypted": false}]},
             {"type": "section", "label": "Extra", "order": 3},
             {"type": "text", "label": "Account no", "value": "12345", "sensitive": 1, "order": 4}
           ]},
          {"uuid": "0b1c2d3e-0000-4000-8000-0000000000d2", "title": "Notes", "category": "note", "template_type": "note.default"}
         ]}
    """.trimIndent()

    @Before
    fun setUp() {
        context.deleteDatabase(CredsDatabase.NAME)
        database = VaultDatabaseFactory(context).open(vaultKey)
        repository = VaultRepository(database, FieldCipher(), AttachmentStore(File(context.cacheDir, "test-attachments")))
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(CredsDatabase.NAME)
    }

    @Test
    fun anImportLandsWithFieldsHistoryTagsAndSearch() = runBlocking {
        repository.createTag("Work")
        val parsed = EnpassParser.parse(export)

        repository.importItems(vaultKey, parsed.items)

        val mail = repository.load(vaultKey, "0b1c2d3e-0000-4000-8000-0000000000d1")!!
        assertEquals(listOf(FieldType.USERNAME, FieldType.PASSWORD, FieldType.SECTION, FieldType.TEXT), mail.fields.map { it.type })
        assertEquals("imported note", mail.note)
        assertEquals(listOf("Work"), mail.tags.map { it.name })
        assertEquals(1, repository.observeTags().first().size)

        val password = mail.fields.single { it.type == FieldType.PASSWORD }
        assertEquals(listOf("previous-Pass0"), repository.fieldHistory(vaultKey, password.uid).map { it.value })
        assertEquals(1_650_000_000_000, repository.fieldHistory(vaultKey, password.uid).single().replacedAt)

        // Searchable by what is public, never by the sensitive account number.
        assertEquals(listOf("Example Mail"), repository.observeItems(VaultFilter(query = "someone")).first().map { it.title })
        assertTrue(repository.observeItems(VaultFilter(query = "12345")).first().isEmpty())
    }

    @Test
    fun aSecondImportIsAllDuplicates() = runBlocking {
        repository.importItems(vaultKey, EnpassParser.parse(export).items)

        val rows = ImportPlanner.plan(EnpassParser.parse(export), repository.importKeys(vaultKey)) { "fresh-uuid" }

        assertTrue(rows.all { it.status == ImportStatus.DUPLICATE })
        assertTrue(rows.all { it.imported.item.uuid == "fresh-uuid" })
    }

    @Test
    fun aFailedImportWritesNothing() = runBlocking {
        val items = EnpassParser.parse(export).items
        // A lock mid-import: deriving from a closed key throws inside the transaction.
        val closedKey = VaultKey(ByteArray(32) { 13 }).also { it.close() }

        runCatching { repository.importItems(closedKey, items) }

        assertEquals(0, repository.observeCounts().first().total)
        assertTrue(repository.observeTags().first().isEmpty())
    }
}
