package dev.creds.vault.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.model.AssociationKind
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.ItemAssociation
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What autofill reads and records, against SQLCipher on a device. */
@RunWith(AndroidJUnit4::class)
class AutofillRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 11 })
    private lateinit var database: CredsDatabase
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        context.deleteDatabase(CredsDatabase.NAME)
        database = VaultDatabaseFactory(context).open(vaultKey)
        repository = VaultRepository(database, FieldCipher())
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(CredsDatabase.NAME)
    }

    @Test
    fun candidatesCarryWebsitesButNeverSecretsAndSkipArchiveAndTrash() = runBlocking {
        login(BANK, "Bank", website = "https://bank.com", hiddenWebsite = "https://secret.example")
        login(OLD, "Old", website = "https://old.com", archived = true)
        login(GONE, "Gone", website = "https://gone.com")
        repository.trash(GONE, now = 2)

        val candidates = repository.autofillCandidates()

        assertEquals(listOf(BANK), candidates.map { it.uuid })
        assertEquals(listOf("https://bank.com"), candidates.single().websites)
    }

    @Test
    fun recordingTrustReplacesTheEarlierKeyAndMarksTheItemUpdated() = runBlocking {
        login(BANK, "Bank")
        repository.recordAssociation(BANK, ItemAssociation(AssociationKind.APP, "com.bank", "AA", 5), now = 5)
        repository.recordAssociation(BANK, ItemAssociation(AssociationKind.APP, "com.bank", "BB", 6), now = 6)
        repository.recordAssociation(BANK, ItemAssociation(AssociationKind.DOMAIN, "bank.com", null, 7), now = 7)

        val associations = repository.associations(BANK).sortedBy { it.kind }
        assertEquals(
            listOf(
                ItemAssociation(AssociationKind.APP, "com.bank", "BB", 6),
                ItemAssociation(AssociationKind.DOMAIN, "bank.com", null, 7),
            ),
            associations,
        )
        assertEquals(7L, repository.load(vaultKey, BANK)!!.updatedAt)
        assertEquals(associations.toSet(), repository.autofillCandidates().single().associations.toSet())
    }

    @Test
    fun removingTrustLeavesOtherAssociations() = runBlocking {
        login(BANK, "Bank")
        repository.recordAssociation(BANK, ItemAssociation(AssociationKind.APP, "com.bank", "AA", 5), now = 5)
        repository.recordAssociation(BANK, ItemAssociation(AssociationKind.DOMAIN, "bank.com", null, 5), now = 5)

        repository.removeAssociation(BANK, AssociationKind.APP, "com.bank", now = 9)

        assertEquals(listOf("bank.com"), repository.associations(BANK).map { it.value })
        assertEquals(9L, repository.load(vaultKey, BANK)!!.updatedAt)
    }

    @Test
    fun purgingAnItemDropsItsTrust() = runBlocking {
        login(BANK, "Bank")
        repository.recordAssociation(BANK, ItemAssociation(AssociationKind.APP, "com.bank", "AA", 5), now = 5)

        repository.purge(BANK)

        assertTrue(repository.associations(BANK).isEmpty())
    }

    private suspend fun login(
        uuid: String,
        title: String,
        website: String = "",
        hiddenWebsite: String = "",
        archived: Boolean = false,
    ) {
        val fields = listOf(
            VaultField(uid = 0, type = FieldType.PASSWORD, label = "Password", value = "pw-Long-1", order = 0),
            VaultField(uid = 0, type = FieldType.URL, label = "Website", value = website, order = 1),
            VaultField(uid = 0, type = FieldType.URL, label = "Admin", value = hiddenWebsite, sensitive = true, order = 2),
        ).filter { it.value.isNotEmpty() }
        repository.save(
            vaultKey,
            VaultItem(uuid = uuid, template = Template.LOGIN, title = title, archived = archived, createdAt = 1, updatedAt = 1, fields = fields),
        )
    }

    private companion object {
        const val BANK = "00000000-0000-0000-0000-0000000000c1"
        const val OLD = "00000000-0000-0000-0000-0000000000c2"
        const val GONE = "00000000-0000-0000-0000-0000000000c3"
    }
}
