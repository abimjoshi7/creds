package dev.creds.vault.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Attachments end to end against SQLCipher: the row, the sealed file, and the rule that
 * files outlive nothing their rows do not.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 42 })
    private val directory = File(context.cacheDir, "test-attachments")
    private lateinit var database: CredsDatabase
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        context.deleteDatabase(CredsDatabase.NAME)
        directory.deleteRecursively()
        database = VaultDatabaseFactory(context).open(vaultKey)
        repository = VaultRepository(database, FieldCipher(), AttachmentStore(directory))
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(CredsDatabase.NAME)
        directory.deleteRecursively()
    }

    @Test
    fun savesLoadsAndOpensAnAttachment() = runBlocking {
        val id = saveWithFile(PLAINTEXT)

        val attachment = repository.load(vaultKey, ITEM)!!.attachments.single()
        assertEquals(id, attachment.id)
        assertEquals("scan.pdf", attachment.name)
        assertEquals("application/pdf", attachment.mimeType)
        assertEquals(PLAINTEXT.size.toLong(), attachment.size)
        assertArrayEquals(PLAINTEXT, repository.openAttachment(vaultKey, ITEM, id))
    }

    @Test
    fun theFileOnDiskIsSealed() = runBlocking {
        val id = saveWithFile(PLAINTEXT)

        val onDisk = File(directory, id).readBytes()
        assertFalse(onDisk.decodeToString().contains(PLAINTEXT.decodeToString()))
    }

    @Test
    fun anAttachmentOpensOnlyThroughItsOwnItem() = runBlocking {
        val id = saveWithFile(PLAINTEXT)
        repository.save(vaultKey, item("other-item"))

        assertNull(repository.openAttachment(vaultKey, "other-item", id))
    }

    @Test
    fun removingAnAttachmentDestroysItsFile() = runBlocking {
        val id = saveWithFile(PLAINTEXT)

        repository.save(vaultKey, item(ITEM, updatedAt = 2), attachments = AttachmentChanges(removedIds = setOf(id)))

        assertTrue(repository.load(vaultKey, ITEM)!!.attachments.isEmpty())
        assertFalse(File(directory, id).exists())
    }

    @Test
    fun anotherItemCannotRemoveAnAttachment() = runBlocking {
        val id = saveWithFile(PLAINTEXT)

        repository.save(vaultKey, item("other-item"), attachments = AttachmentChanges(removedIds = setOf(id)))

        assertEquals(1, repository.load(vaultKey, ITEM)!!.attachments.size)
        assertTrue(File(directory, id).exists())
    }

    @Test
    fun purgingAnItemDeletesItsFiles() = runBlocking {
        val id = saveWithFile(PLAINTEXT)

        repository.trash(ITEM, now = 2)
        assertTrue("trash keeps the file", File(directory, id).exists())

        assertEquals(1, repository.emptyTrash())
        assertFalse(File(directory, id).exists())
    }

    @Test
    fun aFailedSaveLeavesNoFileBehind() = runBlocking {
        // A row whose file is gone lets the same id be written to disk again, and then the
        // insert fails on the primary key — inside the transaction, after the write.
        val id = saveWithFile(PLAINTEXT)
        File(directory, id).delete()

        val result = runCatching {
            repository.save(
                vaultKey,
                item("other-item"),
                attachments = AttachmentChanges(added = listOf(newFile(id, PLAINTEXT))),
            )
        }

        assertTrue(result.isFailure)
        assertFalse(File(directory, id).exists())
        assertNull("the item save rolled back too", repository.load(vaultKey, "other-item"))
    }

    @Test
    fun sweepRemovesFilesWithNoRow() = runBlocking {
        val kept = saveWithFile(PLAINTEXT)
        val orphan = UUID.randomUUID().toString()
        AttachmentStore(directory).write(orphan, byteArrayOf(1, 2, 3))

        assertEquals(1, repository.sweepAttachments())
        assertTrue(File(directory, kept).exists())
        assertFalse(File(directory, orphan).exists())
    }

    private suspend fun saveWithFile(bytes: ByteArray): String {
        val id = UUID.randomUUID().toString()
        repository.save(
            vaultKey,
            item(ITEM),
            attachments = AttachmentChanges(added = listOf(newFile(id, bytes))),
        )
        return id
    }

    private fun newFile(id: String, bytes: ByteArray) = NewAttachment(
        id = id,
        name = "scan.pdf",
        mimeType = "application/pdf",
        bytes = bytes.copyOf(),
        createdAt = 1,
    )

    private fun item(uuid: String, updatedAt: Long = 1) = VaultItem(
        uuid = uuid,
        template = Template.DOCUMENT,
        title = "Documents",
        createdAt = 1,
        updatedAt = updatedAt,
    )

    private companion object {
        const val ITEM = "item-with-files"
        val PLAINTEXT = "%PDF-1.7 recovery codes 1234-5678".toByteArray()
    }
}
