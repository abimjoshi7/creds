package dev.creds.vault.core.domain.audit

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.creds.vault.core.model.Template
import org.junit.jupiter.api.Test

class AuditRulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    @Test
    fun `a clean login with 2FA is healthy`() {
        val report = AuditRules.evaluate(listOf(login(totp = true)), now)

        assertThat(report.findings).isEmpty()
        assertThat(report.healthScore).isEqualTo(100)
        assertThat(report.auditedItems).isEqualTo(1)
    }

    @Test
    fun `weak means a score of two or less`() {
        val report = AuditRules.evaluate(
            listOf(
                login("a", totp = true, password = password(score = 2)),
                login("b", totp = true, password = password(score = 3)),
            ),
            now,
        )

        assertThat(report.findings.map { it.issue to it.itemUuid }).containsExactly(AuditIssue.WEAK to "a")
    }

    @Test
    fun `an unscored password is pending, not weak`() {
        val report = AuditRules.evaluate(listOf(login(totp = true, password = password(score = null))), now)

        assertThat(report.findings).isEmpty()
        assertThat(report.pendingScores).isEqualTo(1)
    }

    @Test
    fun `reuse and breaches carry their detail`() {
        val report = AuditRules.evaluate(
            listOf(login(totp = true, password = password(reusedAcross = 3, breached = true, breachCount = 42))),
            now,
        )

        val breached = report.findings.first { it.issue == AuditIssue.BREACHED }
        val reused = report.findings.first { it.issue == AuditIssue.REUSED }
        assertThat(breached.breachCount).isEqualTo(42)
        assertThat(reused.reusedAcross).isEqualTo(3)
        assertThat(breached.fix).isEqualTo(FixAction.CHANGE_PASSWORD)
    }

    @Test
    fun `stale is more than a year unchanged, and an unknown date is never stale`() {
        val report = AuditRules.evaluate(
            listOf(
                login("old", totp = true, password = password(valueUpdatedAt = now - 366 * day)),
                login("year", totp = true, password = password(valueUpdatedAt = now - 365 * day)),
                login("unknown", totp = true, password = password(valueUpdatedAt = 0)),
            ),
            now,
        )

        assertThat(report.findings.map { it.itemUuid to it.daysUnchanged }).containsExactly("old" to 366)
    }

    @Test
    fun `missing 2FA applies to logins with a website only`() {
        val report = AuditRules.evaluate(
            listOf(
                login("site", totp = false),
                login("no-site", totp = false, url = ""),
                item("server", Template.SERVER, urls = listOf(url("https://ci.example.com"))),
            ),
            now,
        )

        assertThat(report.findings.map { it.issue to it.itemUuid }).containsExactly(AuditIssue.MISSING_2FA to "site")
        assertThat(report.findings.single().fix).isEqualTo(FixAction.ADD_TOTP)
    }

    @Test
    fun `http urls are insecure unless the host is local`() {
        assertThat(AuditRules.isInsecure("http://example.com/login")).isTrue()
        assertThat(AuditRules.isInsecure("  HTTP://Example.com")).isTrue()
        assertThat(AuditRules.isInsecure("https://example.com")).isFalse()
        assertThat(AuditRules.isInsecure("example.com")).isFalse()
        listOf(
            "http://localhost:8080", "http://192.168.1.1", "http://10.0.0.2/admin", "http://172.20.1.1",
            "http://127.0.0.1", "http://router.lan", "http://nas.local", "http://[::1]:3000", "http://ci.home.arpa",
        ).forEach { assertThat(AuditRules.isInsecure(it), it).isFalse() }
        assertThat(AuditRules.isInsecure("http://172.32.0.1")).isTrue()
    }

    @Test
    fun `the https fix changes only the scheme`() {
        assertThat(AuditRules.httpsVersion(" http://example.com/a?b=http://c ")).isEqualTo("https://example.com/a?b=http://c")
        assertThat(AuditRules.httpsVersion("https://example.com")).isEqualTo("https://example.com")
    }

    @Test
    fun `each kind of problem costs an item once and scores average across items`() {
        val report = AuditRules.evaluate(
            listOf(
                // Two weak passwords: one WEAK penalty (50), plus missing 2FA (10) = 40.
                item(
                    "two-weak",
                    Template.LOGIN,
                    passwords = listOf(password(uid = 1, score = 0), password(uid = 2, score = 1)),
                    urls = listOf(url("https://example.com")),
                ),
                login("clean", totp = true),
            ),
            now,
        )

        assertThat(report.healthScore).isEqualTo(70)
    }

    @Test
    fun `an item cannot score below zero`() {
        val report = AuditRules.evaluate(
            listOf(login(totp = false, url = "http://example.com", password = password(score = 0, breached = true, reusedAcross = 2))),
            now,
        )

        assertThat(report.healthScore).isEqualTo(0)
    }

    @Test
    fun `nothing auditable means no score rather than a perfect one`() {
        val report = AuditRules.evaluate(listOf(item("note", Template.NOTE)), now)

        assertThat(report.healthScore).isNull()
        assertThat(report.auditedItems).isEqualTo(0)
    }

    @Test
    fun `findings are ordered by severity then title`() {
        val report = AuditRules.evaluate(
            listOf(
                login("z", title = "Zeta", totp = false),
                login("a", title = "alpha", totp = true, password = password(breached = true)),
                login("b", title = "Beta", totp = false),
            ),
            now,
        )

        assertThat(report.findings.map { it.issue to it.itemTitle }).containsExactly(
            AuditIssue.BREACHED to "alpha",
            AuditIssue.MISSING_2FA to "Beta",
            AuditIssue.MISSING_2FA to "Zeta",
        )
    }

    private fun login(
        uuid: String = "login",
        title: String = uuid,
        totp: Boolean,
        url: String = "https://example.com",
        password: AuditedPassword = password(),
    ) = item(uuid, Template.LOGIN, listOf(password), if (url.isEmpty()) emptyList() else listOf(url(url)), totp, title)

    private fun item(
        uuid: String,
        template: Template,
        passwords: List<AuditedPassword> = emptyList(),
        urls: List<AuditedUrl> = emptyList(),
        hasTotp: Boolean = false,
        title: String = uuid,
    ) = AuditedItem(uuid, title, template, passwords, urls, hasTotp)

    private fun password(
        uid: Long = 1,
        score: Int? = 4,
        breached: Boolean = false,
        breachCount: Int? = null,
        reusedAcross: Int = 1,
        valueUpdatedAt: Long = now - day,
    ) = AuditedPassword(uid, "Password", score, breached, breachCount, reusedAcross, valueUpdatedAt)

    private fun url(value: String) = AuditedUrl(fieldUid = 9, label = "Website", value = value)
}
