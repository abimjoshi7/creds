package dev.creds.vault.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.data.repository.VaultRepository.Companion.GENERATOR_HISTORY_SIZE
import dev.creds.vault.core.data.repository.VaultRepository.Companion.GENERATOR_HISTORY_TTL_MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The generator's recent-values list, against SQLCipher on a device. */
@RunWith(AndroidJUnit4::class)
class VaultRepositoryGeneratorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 9 })
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
    fun valuesComeBackNewestFirstAndAreStoredEncrypted() = runBlocking {
        repository.recordGenerated(vaultKey, "first-Value1", now = 100)
        repository.recordGenerated(vaultKey, "second-Value2", now = 200)

        val history = repository.generatedHistory(vaultKey, now = 300)
        assertEquals(listOf("second-Value2", "first-Value1"), history.map { it.value })
        assertEquals(listOf(200L, 100L), history.map { it.createdAt })

        val raw = database.generatorHistoryDao().all().map { String(it.valueEnc, Charsets.ISO_8859_1) }
        assertFalse(raw.any { it.contains("Value") })
    }

    @Test
    fun takingTheSameValueTwiceInARowRecordsItOnce() = runBlocking {
        repository.recordGenerated(vaultKey, "same", now = 1)
        repository.recordGenerated(vaultKey, "same", now = 2)
        repository.recordGenerated(vaultKey, "other", now = 3)
        repository.recordGenerated(vaultKey, "same", now = 4)

        assertEquals(listOf("same", "other", "same"), repository.generatedHistory(vaultKey, now = 5).map { it.value })
    }

    @Test
    fun onlyTheNewestAreKept() = runBlocking {
        repeat(GENERATOR_HISTORY_SIZE + 5) { repository.recordGenerated(vaultKey, "value-$it", now = it.toLong()) }

        val history = repository.generatedHistory(vaultKey, now = 100)
        assertEquals(GENERATOR_HISTORY_SIZE, history.size)
        assertEquals("value-${GENERATOR_HISTORY_SIZE + 4}", history.first().value)
        assertEquals("value-5", history.last().value)
    }

    @Test
    fun valuesOlderThanADayAreDeletedOnRead() = runBlocking {
        repository.recordGenerated(vaultKey, "old", now = 0)
        repository.recordGenerated(vaultKey, "recent", now = GENERATOR_HISTORY_TTL_MS)

        val history = repository.generatedHistory(vaultKey, now = GENERATOR_HISTORY_TTL_MS + 1)

        assertEquals(listOf("recent"), history.map { it.value })
        assertEquals(1, database.generatorHistoryDao().all().size)
    }

    @Test
    fun deleteAndClear() = runBlocking {
        repository.recordGenerated(vaultKey, "a", now = 1)
        repository.recordGenerated(vaultKey, "b", now = 2)
        val newest = repository.generatedHistory(vaultKey, now = 3).first()

        repository.deleteGenerated(newest.id)
        assertEquals(listOf("a"), repository.generatedHistory(vaultKey, now = 3).map { it.value })

        repository.clearGenerated()
        assertTrue(repository.generatedHistory(vaultKey, now = 3).isEmpty())
    }
}
