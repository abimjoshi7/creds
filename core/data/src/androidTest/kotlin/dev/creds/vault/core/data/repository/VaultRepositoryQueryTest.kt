package dev.creds.vault.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.attachments.AttachmentStore
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultFilter
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The vault list's queries, run against SQLCipher on a device.
 *
 * `VaultQueryTest` pins the SQL on the host; this proves it selects the right rows —
 * including the FTS5 subquery, which only a real SQLCipher build can execute.
 */
@RunWith(AndroidJUnit4::class)
class VaultRepositoryQueryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 42 })
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
    fun theDefaultViewHidesArchiveAndTrashAndSortsByTitle() = runBlocking {
        seedVault()

        assertEquals(listOf("aws", "Bank", "Gmail", "Visa"), titles(VaultFilter()))
    }

    @Test
    fun favorites() = runBlocking {
        seedVault()

        assertEquals(listOf("Gmail"), titles(VaultFilter(smartList = SmartList.FAVORITES)))
    }

    @Test
    fun archiveExcludesTrashedItems() = runBlocking {
        seedVault()

        assertEquals(listOf("Old forum"), titles(VaultFilter(smartList = SmartList.ARCHIVE)))
    }

    @Test
    fun trash() = runBlocking {
        seedVault()

        assertEquals(listOf("Deleted"), titles(VaultFilter(smartList = SmartList.TRASH)))
    }

    @Test
    fun templateFilter() = runBlocking {
        seedVault()

        assertEquals(listOf("Visa"), titles(VaultFilter(template = Template.CARD)))
    }

    @Test
    fun tagsNarrowWithAnd() = runBlocking {
        seedVault()
        val work = tag("work")
        val finance = tag("finance")
        repository.setItemTags(GMAIL, setOf(work.id), now = 2)
        repository.setItemTags(BANK, setOf(work.id, finance.id), now = 2)

        assertEquals(listOf("Bank", "Gmail"), titles(VaultFilter(tagIds = setOf(work.id))))
        assertEquals(listOf("Bank"), titles(VaultFilter(tagIds = setOf(work.id, finance.id))))
    }

    @Test
    fun searchUsesTheIndexAndComposesWithOtherFilters() = runBlocking {
        seedVault()

        assertEquals(listOf("Gmail"), titles(VaultFilter(query = "alice")))
        assertEquals(listOf("Bank", "Gmail"), titles(VaultFilter(query = "example")))
        assertEquals(
            listOf("Bank"),
            titles(VaultFilter(template = Template.BANK_ACCOUNT, query = "example")),
        )
    }

    @Test
    fun searchMatchesNotesButNeverSecrets() = runBlocking {
        seedVault()

        assertEquals(listOf("aws"), titles(VaultFilter(query = "recovery")))
        assertEquals(emptyList<String>(), titles(VaultFilter(query = "hunter2")))
    }

    @Test
    fun archivedItemsAreSearchableFromTheArchive() = runBlocking {
        seedVault()

        assertEquals(
            listOf("Old forum"),
            titles(VaultFilter(smartList = SmartList.ARCHIVE, query = "forum")),
        )
        assertEquals(emptyList<String>(), titles(VaultFilter(query = "forum")))
    }

    @Test
    fun trashIsSearchedByTitleAndSubtitle() = runBlocking {
        seedVault()

        assertEquals(listOf("Deleted"), titles(VaultFilter(smartList = SmartList.TRASH, query = "dele")))
        assertEquals(
            listOf("Deleted"),
            titles(VaultFilter(smartList = SmartList.TRASH, query = "gone@")),
        )
        // Wildcards are literals: "100%" must not match every title.
        assertEquals(
            emptyList<String>(),
            titles(VaultFilter(smartList = SmartList.TRASH, query = "1%")),
        )
    }

    @Test
    fun aStrayPunctuationQueryDoesNotEmptyTheList() = runBlocking {
        seedVault()

        assertEquals(listOf("aws", "Bank", "Gmail", "Visa"), titles(VaultFilter(query = ",")))
    }

    @Test
    fun rowsCarryTheirTagsAlphabetically() = runBlocking {
        seedVault()
        val zeta = tag("zeta")
        val alpha = tag("Alpha")
        repository.setItemTags(GMAIL, setOf(zeta.id, alpha.id), now = 2)

        val gmail = repository.observeItems(VaultFilter()).first().single { it.uuid == GMAIL }

        assertEquals(listOf("Alpha", "zeta"), gmail.tags.map { it.name })
    }

    @Test
    fun countsReflectEveryList() = runBlocking {
        seedVault()
        val work = tag("work")
        repository.setItemTags(GMAIL, setOf(work.id), now = 2)
        repository.setItemTags(ARCHIVED, setOf(work.id), now = 2)

        val counts = repository.observeCounts().first()

        assertEquals(4, counts.all)
        assertEquals(1, counts.favorites)
        assertEquals(1, counts.archive)
        assertEquals(1, counts.trash)
        assertEquals(6, counts.total)
        assertEquals(2, counts.byTemplate[Template.LOGIN])
        assertEquals(1, counts.byTemplate[Template.CARD])
        // The archived item is tagged too, but tag counts follow the default view.
        assertEquals(1, counts.byTag[work.id])
    }

    @Test
    fun countsOfAnEmptyVaultAreZeroNotNull() = runBlocking {
        val counts = repository.observeCounts().first()

        assertEquals(0, counts.total)
        assertTrue(counts.byTemplate.isEmpty())
    }

    @Test
    fun theListUpdatesWhenAnItemIsTagged() = runBlocking {
        seedVault()
        val work = tag("work")
        val filter = VaultFilter(tagIds = setOf(work.id))

        val subscribed = CompletableDeferred<List<String>>()
        val tagged = async {
            withTimeout(5_000) {
                repository.observeItems(filter)
                    .onEach { rows -> subscribed.complete(rows.map { it.uuid }) }
                    .first { rows -> rows.any { it.uuid == BANK } }
            }
        }
        // The write lands only after the collector has seen the untagged state, so the
        // second emission can only have come from Room's invalidation tracker.
        assertEquals(emptyList<String>(), subscribed.await())
        repository.setItemTags(BANK, setOf(work.id), now = 2)

        assertEquals(listOf("Bank"), tagged.await().map { it.title })
    }

    @Test
    fun favoriteAndArchiveToggle() = runBlocking {
        seedVault()

        repository.setFavorite(BANK, favorite = true, now = 5)
        repository.setArchived(VISA, archived = true, now = 5)

        assertEquals(listOf("Bank", "Gmail"), titles(VaultFilter(smartList = SmartList.FAVORITES)))
        assertEquals(listOf("Old forum", "Visa"), titles(VaultFilter(smartList = SmartList.ARCHIVE)))
        assertEquals(5L, database.itemDao().byUuid(BANK)!!.updatedAt)
    }

    @Test
    fun restoringFromTrashMakesAnItemSearchableAgain() = runBlocking {
        seedVault()

        repository.restore(vaultKey, TRASHED, now = 9)

        assertEquals(listOf("Deleted"), titles(VaultFilter(query = "Deleted")))
    }

    @Test
    fun emptyTrashPurgesOnlyTrashedItems() = runBlocking {
        seedVault()

        assertEquals(1, repository.emptyTrash())

        assertEquals(0, repository.observeCounts().first().trash)
        assertEquals(4, repository.observeCounts().first().all)
    }

    @Test
    fun tagNamesAreNormalisedAndUniqueIgnoringCase() = runBlocking {
        val first = repository.createTag("  #Work  ")

        assertEquals("Work", (first as TagResult.Saved).tag.name)
        assertEquals(TagResult.Duplicate("Work"), repository.createTag("work"))
        assertEquals(TagResult.Blank, repository.createTag("#"))
        assertEquals(1, repository.observeTags().first().size)
    }

    @Test
    fun renamingATagKeepsItsItems() = runBlocking {
        seedVault()
        val work = tag("work")
        repository.setItemTags(GMAIL, setOf(work.id), now = 2)

        val renamed = repository.updateTag(work.copy(name = "Job", color = 0xFF00FF00.toInt()))

        assertTrue(renamed is TagResult.Saved)
        val gmail = repository.observeItems(VaultFilter(tagIds = setOf(work.id))).first().single()
        assertEquals(listOf(Tag(work.id, "Job", 0xFF00FF00.toInt())), gmail.tags)
    }

    @Test
    fun renamingATagOntoAnotherIsRefusedButChangingItsCaseIsNot() = runBlocking {
        val work = tag("work")
        tag("home")

        assertEquals(TagResult.Duplicate("home"), repository.updateTag(work.copy(name = "HOME")))
        assertTrue(repository.updateTag(work.copy(name = "Work")) is TagResult.Saved)
    }

    @Test
    fun deletingATagKeepsItsItemsAndMarksThemUpdated() = runBlocking {
        seedVault()
        val work = tag("work")
        repository.setItemTags(GMAIL, setOf(work.id), now = 2)

        repository.deleteTag(work.id, now = 77)

        val gmail = repository.observeItems(VaultFilter()).first().single { it.uuid == GMAIL }
        assertTrue(gmail.tags.isEmpty())
        assertEquals(77L, gmail.updatedAt)
        assertEquals(4, repository.observeCounts().first().all)
    }

    @Test
    fun updatingADeletedTagReportsNotFound() = runBlocking {
        val work = tag("work")
        repository.deleteTag(work.id, now = 1)

        assertEquals(TagResult.NotFound, repository.updateTag(work.copy(name = "x")))
    }

    private suspend fun titles(filter: VaultFilter): List<String> =
        repository.observeItems(filter).first().map { it.title }

    private suspend fun tag(name: String): Tag =
        (repository.createTag(name) as TagResult.Saved).tag

    /**
     * Six items across every list. "aws" is lower-case on purpose: the list must sort
     * case-insensitively, or it lands after every capitalised title.
     */
    private suspend fun seedVault() {
        save(GMAIL, Template.LOGIN, "Gmail", favorite = true, username = "alice@example.com")
        save(BANK, Template.BANK_ACCOUNT, "Bank", username = "bob@example.com")
        save(VISA, Template.CARD, "Visa", username = "card holder")
        save(AWS, Template.LOGIN, "aws", username = "root", note = "recovery codes inside")
        save(ARCHIVED, Template.LOGIN, "Old forum", archived = true, username = "old")
        save(TRASHED, Template.LOGIN, "Deleted", username = "gone@example.org")
        repository.trash(TRASHED, now = 3)
    }

    private suspend fun save(
        uuid: String,
        template: Template,
        title: String,
        username: String,
        favorite: Boolean = false,
        archived: Boolean = false,
        note: String = "",
    ) {
        repository.save(
            vaultKey,
            VaultItem(
                uuid = uuid,
                template = template,
                title = title,
                subtitle = username,
                note = note,
                favorite = favorite,
                archived = archived,
                createdAt = 1,
                updatedAt = 1,
                fields = listOf(
                    VaultField(uid = 0, type = FieldType.USERNAME, label = "Username", value = username, order = 0),
                    VaultField(uid = 0, type = FieldType.PASSWORD, label = "Password", value = "hunter2", order = 1),
                ),
            ),
        )
    }

    private companion object {
        const val GMAIL = "00000000-0000-0000-0000-000000000001"
        const val BANK = "00000000-0000-0000-0000-000000000002"
        const val VISA = "00000000-0000-0000-0000-000000000003"
        const val AWS = "00000000-0000-0000-0000-000000000004"
        const val ARCHIVED = "00000000-0000-0000-0000-000000000005"
        const val TRASHED = "00000000-0000-0000-0000-000000000006"
    }
}
