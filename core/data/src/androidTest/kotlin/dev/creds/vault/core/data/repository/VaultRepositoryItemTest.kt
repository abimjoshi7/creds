package dev.creds.vault.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opening, editing and watching a single item, against SQLCipher on a device.
 */
@RunWith(AndroidJUnit4::class)
class VaultRepositoryItemTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 7 })
    private lateinit var database: CredsDatabase
    private lateinit var repository: VaultRepository

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
    fun changingAPasswordKeepsTheOldOneInHistory() = runBlocking {
        val saved = saveLogin(password = "first-Pass1")

        resave(saved, now = 10) { if (it.type == FieldType.PASSWORD) it.copy(value = "second-Pass2") else it }
        val reloaded = requireItem()
        resave(reloaded, now = 20) { if (it.type == FieldType.PASSWORD) it.copy(value = "third-Pass3") else it }

        val password = requireItem().primaryPassword!!
        assertEquals("third-Pass3", password.value)
        assertEquals(2, repository.fieldHistoryCount(password.uid))
        val history = repository.fieldHistory(vaultKey, password.uid)
        assertEquals(listOf("second-Pass2", "first-Pass1"), history.map { it.value })
        assertEquals(listOf(20L, 10L), history.map { it.replacedAt })
    }

    @Test
    fun unchangedAndNonSensitiveValuesLeaveNoHistory() = runBlocking {
        val saved = saveLogin(password = "same-Pass1")

        resave(saved, now = 10) { if (it.type == FieldType.USERNAME) it.copy(value = "renamed") else it }

        val item = requireItem()
        item.fields.forEach { assertEquals(0, repository.fieldHistoryCount(it.uid)) }
        assertEquals("renamed", item.primaryUsername!!.value)
    }

    @Test
    fun aRemovedFieldIsTombstonedAndNoLongerLoads() = runBlocking {
        val saved = saveLogin(password = "p")
        val username = saved.primaryUsername!!

        repository.save(vaultKey, saved.copy(updatedAt = 5, fields = saved.fields - username))

        assertTrue(requireItem().fields.none { it.uid == username.uid })
        assertTrue(database.fieldDao().byUid(username.uid)!!.deleted)
    }

    @Test
    fun sectionHeadingsSurviveARoundTrip() = runBlocking {
        val item = VaultItem(
            uuid = UUID,
            template = Template.MISC,
            title = "Imported",
            createdAt = 1,
            updatedAt = 1,
            fields = listOf(
                VaultField(uid = 0, type = FieldType.SECTION, label = "Recovery", value = "", order = 0),
                VaultField(uid = 0, type = FieldType.TEXT, label = "Code", value = "abc", sensitive = true, order = 1),
            ),
        )
        repository.save(vaultKey, item)

        val loaded = requireItem()
        repository.save(vaultKey, loaded.copy(updatedAt = 2))

        assertEquals(listOf(FieldType.SECTION, FieldType.TEXT), requireItem().fields.map { it.type })
    }

    @Test
    fun changesToTheItemOrItsTagsAreSignalledAndDeletionEndsIt() = runBlocking {
        saveLogin(password = "p")
        val work = (repository.createTag("work") as TagResult.Saved).tag
        val signals = Channel<Boolean>(Channel.UNLIMITED)
        val watcher = launch { repository.observeItemChanges(UUID).collect(signals::send) }

        // Each write waits for its own signal, so Room cannot conflate two into one.
        assertEquals(true, signals.receiveWithin())
        repository.setItemTags(UUID, setOf(work.id), now = 2)
        assertEquals(true, signals.receiveWithin())
        repository.updateTag(work.copy(name = "job"))
        assertEquals(true, signals.receiveWithin())
        repository.purge(UUID)
        assertEquals(false, signals.receiveWithin())

        watcher.cancel()
    }

    @Test
    fun writesToOtherItemsAreNotSignalled() = runBlocking {
        saveLogin(password = "p")
        val signals = Channel<Boolean>(Channel.UNLIMITED)
        val watcher = launch { repository.observeItemChanges(UUID).collect(signals::send) }
        assertEquals(true, signals.receiveWithin())

        repository.save(
            vaultKey,
            VaultItem(uuid = OTHER, template = Template.NOTE, title = "Other", createdAt = 1, updatedAt = 1),
        )
        repository.setFavorite(OTHER, favorite = true, now = 9)

        assertNull(withTimeoutOrNull(1_000) { signals.receive() })
        watcher.cancel()
    }

    private suspend fun Channel<Boolean>.receiveWithin(): Boolean = withTimeout(5_000) { receive() }

    private suspend fun saveLogin(password: String): VaultItem {
        repository.save(
            vaultKey,
            VaultItem(
                uuid = UUID,
                template = Template.LOGIN,
                title = "Example",
                createdAt = 1,
                updatedAt = 1,
                fields = listOf(
                    VaultField(uid = 0, type = FieldType.USERNAME, label = "Username", value = "alice", order = 0),
                    VaultField(uid = 0, type = FieldType.PASSWORD, label = "Password", value = password, order = 1),
                ),
            ),
        )
        return requireItem()
    }

    private suspend fun resave(item: VaultItem, now: Long, edit: (VaultField) -> VaultField) {
        repository.save(vaultKey, item.copy(updatedAt = now, fields = item.fields.map(edit)))
    }

    private suspend fun requireItem(): VaultItem =
        repository.load(vaultKey, UUID) ?: error("item $UUID was not saved")

    private companion object {
        const val UUID = "00000000-0000-0000-0000-0000000000a1"
        const val OTHER = "00000000-0000-0000-0000-0000000000a2"
    }
}
