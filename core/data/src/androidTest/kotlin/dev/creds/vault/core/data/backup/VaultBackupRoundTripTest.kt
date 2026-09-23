package dev.creds.vault.core.data.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lambdapioneer.argon2kt.Argon2Kt
import dev.creds.vault.core.crypto.Argon2idPasswordHasher
import dev.creds.vault.core.crypto.KdfParams
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.data.repository.AttachmentChanges
import dev.creds.vault.core.data.repository.NewAttachment
import dev.creds.vault.core.data.repository.TagResult
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.domain.importer.ImportPlanner
import dev.creds.vault.core.model.AssociationKind
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.ItemAssociation
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * A whole vault out to a `.vault` file and into a different, empty vault, on a device with
 * the real Argon2id — the path a user takes to move to a new phone or a new install.
 */
@RunWith(AndroidJUnit4::class)
class VaultBackupRoundTripTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val directory = File(context.cacheDir, "backup-attachments")
    private val backup = VaultBackup(Argon2idPasswordHasher(Argon2Kt()))

    // Cheap parameters: this tests the format and the plumbing, not Argon2id's cost.
    private val params = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1)

    private lateinit var database: CredsDatabase

    @Before
    @After
    fun clean() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(CredsDatabase.NAME)
        directory.deleteRecursively()
    }

    private fun open(key: VaultKey): VaultRepository {
        if (::database.isInitialized) database.close()
        database = VaultDatabaseFactory(context).open(key)
        return VaultRepository(database, FieldCipher(), AttachmentStore(directory))
    }

    @Test
    fun everythingSurvivesTheTripToAnotherVault() = runBlocking {
        val sourceKey = VaultKey(ByteArray(32) { 1 })
        val source = open(sourceKey)
        val tag = (source.createTag("work", color = 0x336699) as TagResult.Saved).tag
        val file = "%PDF recovery sheet".toByteArray()

        source.save(
            sourceKey,
            VaultItem(
                uuid = MAIL,
                template = Template.LOGIN,
                title = "Mail",
                note = "recovery codes in the attachment",
                favorite = true,
                createdAt = 1,
                updatedAt = 2,
                fields = listOf(
                    VaultField(uid = 0, type = FieldType.USERNAME, label = "Username", value = "someone", order = 0),
                    VaultField(uid = 0, type = FieldType.PASSWORD, label = "Password", value = "first-Pass1", order = 1),
                ),
            ),
            tags = listOf(tag),
            attachments = AttachmentChanges(
                added = listOf(NewAttachment(UUID.randomUUID().toString(), "sheet.pdf", "application/pdf", file.copyOf(), 3)),
            ),
        )
        // Change the password so it gains history.
        val saved = source.load(sourceKey, MAIL)!!
        source.save(sourceKey, saved.copy(updatedAt = 5, fields = saved.fields.map {
            if (it.type == FieldType.PASSWORD) it.copy(value = "second-Pass2") else it
        }))
        source.recordAssociation(MAIL, ItemAssociation(AssociationKind.DOMAIN, "mail.example", null, 6), now = 6)
        source.save(sourceKey, VaultItem(uuid = OLD, template = Template.NOTE, title = "Old", trashed = true, createdAt = 1, updatedAt = 1))

        val out = ByteArrayOutputStream()
        val written = backup.export(source, sourceKey, "backup-Password9".toCharArray(), out, now = 10, params = params)
        assertEquals(2, written)
        val bytes = out.toByteArray()
        assertFalse("plaintext leaked into the file", String(bytes, Charsets.ISO_8859_1).contains("second-Pass2"))

        // A different vault, under a different key, starting empty.
        clean()
        val targetKey = VaultKey(ByteArray(32) { 2 })
        val target = open(targetKey)
        val opened = backup.open(ByteArrayInputStream(bytes), "backup-Password9".toCharArray())
        val rows = ImportPlanner.plan(opened.parsed, target.importKeys(targetKey)) { UUID.randomUUID().toString() }
        target.importItems(targetKey, rows.map { it.imported }, opened.attachments { ByteArrayInputStream(bytes) })
        opened.close()

        val mail = target.load(targetKey, MAIL)!!
        assertEquals("Mail", mail.title)
        assertTrue(mail.favorite)
        assertEquals("recovery codes in the attachment", mail.note)
        assertEquals("second-Pass2", mail.primaryPassword!!.value)
        assertEquals(listOf("first-Pass1"), target.fieldHistory(targetKey, mail.primaryPassword!!.uid).map { it.value })
        assertEquals(listOf("work"), mail.tags.map { it.name })
        assertEquals(0x336699, target.observeTags().first().single().color)
        assertEquals(listOf("mail.example"), target.associations(MAIL).map { it.value })

        val attachment = mail.attachments.single()
        assertEquals("sheet.pdf", attachment.name)
        assertArrayEquals(file, target.openAttachment(targetKey, MAIL, attachment.id))

        assertTrue("trash comes across as trash", target.load(targetKey, OLD)!!.trashed)
    }

    @Test
    fun theWrongPasswordOpensNothing() = runBlocking {
        val key = VaultKey(ByteArray(32) { 1 })
        val source = open(key)
        source.save(key, VaultItem(uuid = MAIL, template = Template.NOTE, title = "N", createdAt = 1, updatedAt = 1))
        val out = ByteArrayOutputStream()
        backup.export(source, key, "right-Password1".toCharArray(), out, now = 1, params = params)

        val error = runCatching { backup.open(ByteArrayInputStream(out.toByteArray()), "wrong-Password1".toCharArray()) }
        assertTrue(error.exceptionOrNull() is BackupException)
    }

    private companion object {
        const val MAIL = "0b1c2d3e-0000-4000-8000-0000000000a1"
        const val OLD = "0b1c2d3e-0000-4000-8000-0000000000a2"
    }
}
