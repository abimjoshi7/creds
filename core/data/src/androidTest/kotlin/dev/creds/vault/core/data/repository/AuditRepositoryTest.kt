package dev.creds.vault.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.domain.audit.AuditIssue
import dev.creds.vault.core.domain.breach.BreachRangeSource
import dev.creds.vault.core.domain.breach.HibpRange
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultFilter
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audit against SQLCipher on a device: what gets scored, what the report finds, and
 * that the smart lists and drawer counts agree with it.
 */
@RunWith(AndroidJUnit4::class)
class AuditRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 5 })
    private lateinit var database: CredsDatabase
    private lateinit var repository: VaultRepository

    /** Short means weak. Real zxcvbn lives in :core:crypto and is tested there. */
    private val estimator = PasswordStrengthEstimator { chars ->
        PasswordStrength(score = if (chars.size < 10) 1 else 4, guessesLog10 = chars.size.toDouble(), crackTimeDisplay = "")
    }
    private val offlineList = setOf("password1")

    private val now = 400L * DAY

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
    fun onlyChangedPasswordsInLiveItemsAreScored() = runBlocking {
        login(MAIL, "Mail", password = "long-enough-Pass1")
        login(BANK, "Bank", password = "short", pin = "1234")
        login(GONE, "Gone", password = "whatever-long-1")
        repository.trash(GONE, now = 2)

        assertEquals(2, rescore())
        assertEquals(0, rescore())

        val bank = repository.load(vaultKey, BANK)!!
        // Changed after the last scoring run, which is what makes it pending again.
        repository.save(vaultKey, bank.copy(updatedAt = now + 1, fields = bank.fields.map {
            if (it.type == FieldType.PASSWORD) it.copy(value = "changed-and-long", valueUpdatedAt = now + 1) else it
        }))
        assertEquals(1, rescore())
    }

    @Test
    fun theReportFindsEachIssue() = runBlocking {
        login(MAIL, "Mail", password = "shared-long-Pass1", url = "https://mail.example.com", totp = "JBSWY3DPEHPK3PXP")
        login(FORUM, "Forum", password = "shared-long-Pass1", url = "http://forum.example.com", totp = "JBSWY3DPEHPK3PXP")
        login(BANK, "Bank", password = "short", url = "https://bank.example.com", totp = "JBSWY3DPEHPK3PXP")
        login(OLD, "Old", password = "password1", url = "", valueUpdatedAt = 1)
        rescore()

        val report = repository.audit.report(now)
        val found = report.findings.map { it.issue to it.itemUuid }.toSet()

        assertEquals(
            setOf(
                AuditIssue.REUSED to MAIL,
                AuditIssue.REUSED to FORUM,
                AuditIssue.INSECURE_URL to FORUM,
                AuditIssue.WEAK to BANK,
                AuditIssue.WEAK to OLD,
                AuditIssue.BREACHED to OLD,
                AuditIssue.STALE to OLD,
            ),
            found,
        )
        assertEquals(0, report.pendingScores)
        assertEquals(4, report.auditedItems)
    }

    @Test
    fun trashedCopiesAreNotReuseAndArchivedItemsAreNotReported() = runBlocking {
        login(MAIL, "Mail", password = "shared-long-Pass1")
        login(GONE, "Gone", password = "shared-long-Pass1")
        login(OLD, "Old", password = "short", archived = true)
        repository.trash(GONE, now = 2)
        rescore()

        val report = repository.audit.report(now)

        assertTrue(report.findings.none { it.issue == AuditIssue.REUSED })
        assertTrue(report.findings.none { it.itemUuid == OLD })
    }

    @Test
    fun smartListsAndCountsAgreeWithTheReport() = runBlocking {
        login(MAIL, "Mail", password = "shared-long-Pass1")
        login(FORUM, "Forum", password = "shared-long-Pass1")
        login(BANK, "Bank", password = "short")
        login(OLD, "Old", password = "password1")
        rescore()

        assertEquals(listOf("Bank", "Old"), titles(SmartList.WEAK))
        assertEquals(listOf("Forum", "Mail"), titles(SmartList.REUSED))
        assertEquals(listOf("Old"), titles(SmartList.BREACHED))

        val counts = repository.observeCounts().first()
        assertEquals(2, counts.weak)
        assertEquals(2, counts.reused)
        assertEquals(1, counts.breached)
    }

    @Test
    fun anEditedPasswordLeavesWeakImmediately() = runBlocking {
        login(BANK, "Bank", password = "short")
        rescore()
        assertEquals(listOf("Bank"), titles(SmartList.WEAK))

        val bank = repository.load(vaultKey, BANK)!!
        repository.save(vaultKey, bank.copy(updatedAt = now + 1, fields = bank.fields.map {
            if (it.type == FieldType.PASSWORD) it.copy(value = "now-a-long-one", valueUpdatedAt = now + 1) else it
        }))

        assertEquals(emptyList<String>(), titles(SmartList.WEAK))
        assertEquals(1, repository.audit.report(now + 2).pendingScores)
    }

    @Test
    fun onlineCheckSendsOnePrefixPerRequestAndRecordsCounts() = runBlocking {
        login(MAIL, "Mail", password = "long-enough-Pass1")
        login(BANK, "Bank", password = "long-enough-Pass1")
        login(OLD, "Old", password = "another-long-Pass2")
        rescore()

        val pwned = HibpRange.sha1Hex("another-long-Pass2")
        val requested = mutableListOf<String>()
        val source = BreachRangeSource { prefix ->
            requested += prefix
            if (prefix == HibpRange.prefix(pwned)) "${pwned.drop(5)}:31\r\nABCDEF0123456789ABCDEF0123456789ABC:0" else ""
        }

        val result = repository.audit.checkOnline(vaultKey, source, now)

        assertEquals(3, result.checked)
        assertEquals(1, result.breached)
        assertEquals(requested.distinct(), requested)
        assertTrue(requested.all { it.length == 5 })
        val breached = repository.audit.report(now).findings.single { it.issue == AuditIssue.BREACHED }
        assertEquals(OLD, breached.itemUuid)
        assertEquals(31, breached.breachCount)

        requested.clear()
        assertEquals(0, repository.audit.checkOnline(vaultKey, source, now).checked)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun anOnlineMissNeverClearsAnOfflineHit() = runBlocking {
        login(OLD, "Old", password = "password1")
        rescore()

        repository.audit.checkOnline(vaultKey, { "" }, now)

        assertEquals(listOf("Old"), titles(SmartList.BREACHED))
    }

    @Test
    fun theHttpsFixRewritesTheFieldAndSubtitle() = runBlocking {
        login(FORUM, "Forum", password = "long-enough-Pass1", url = "http://forum.example.com", username = "")
        val url = repository.load(vaultKey, FORUM)!!.fields.single { it.type == FieldType.URL }

        assertTrue(repository.updateFieldValue(vaultKey, FORUM, url.uid, "https://forum.example.com", now))

        val item = repository.load(vaultKey, FORUM)!!
        assertEquals("https://forum.example.com", item.fields.single { it.uid == url.uid }.value)
        assertEquals("https://forum.example.com", item.subtitle)
        assertTrue(repository.audit.report(now).findings.none { it.issue == AuditIssue.INSECURE_URL })
    }

    private suspend fun rescore(): Int =
        repository.audit.rescore(vaultKey, estimator, offlineList::contains, now)

    private suspend fun titles(list: SmartList): List<String> =
        repository.observeItems(VaultFilter(smartList = list)).first().map { it.title }

    private suspend fun login(
        uuid: String,
        title: String,
        password: String,
        url: String = "https://example.com",
        totp: String = "",
        pin: String = "",
        username: String = "someone",
        archived: Boolean = false,
        valueUpdatedAt: Long = now - DAY,
    ) {
        val fields = listOf(
            VaultField(uid = 0, type = FieldType.USERNAME, label = "Username", value = username, order = 0),
            VaultField(uid = 0, type = FieldType.PASSWORD, label = "Password", value = password, order = 1, valueUpdatedAt = valueUpdatedAt),
            VaultField(uid = 0, type = FieldType.URL, label = "Website", value = url, order = 2),
            VaultField(uid = 0, type = FieldType.TOTP, label = "One-time code", value = totp, order = 3),
            VaultField(uid = 0, type = FieldType.PIN, label = "PIN", value = pin, order = 4),
        ).filter { it.value.isNotEmpty() }
        repository.save(
            vaultKey,
            VaultItem(
                uuid = uuid,
                template = Template.LOGIN,
                title = title,
                subtitle = username.ifEmpty { url },
                archived = archived,
                createdAt = 1,
                updatedAt = 1,
                fields = fields,
            ),
        )
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val MAIL = "00000000-0000-0000-0000-0000000000b1"
        const val BANK = "00000000-0000-0000-0000-0000000000b2"
        const val FORUM = "00000000-0000-0000-0000-0000000000b3"
        const val OLD = "00000000-0000-0000-0000-0000000000b4"
        const val GONE = "00000000-0000-0000-0000-0000000000b5"
    }
}
