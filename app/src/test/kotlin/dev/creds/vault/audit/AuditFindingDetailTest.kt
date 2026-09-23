package dev.creds.vault.audit

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.creds.vault.core.domain.audit.AuditFinding
import dev.creds.vault.core.domain.audit.AuditIssue
import org.junit.jupiter.api.Test

class AuditFindingDetailTest {

    private fun finding(issue: AuditIssue) =
        AuditFinding(issue = issue, itemUuid = "u", itemTitle = "Mail", fieldUid = 1, fieldLabel = "Password")

    @Test
    fun `each issue explains itself in one line`() {
        assertThat(finding(AuditIssue.BREACHED).detail()).isEqualTo("Password appears in a list of breached passwords")
        assertThat(finding(AuditIssue.BREACHED).copy(breachCount = 1234).detail())
            .isEqualTo("Password seen ${"%,d".format(1234)} times in breaches")
        assertThat(finding(AuditIssue.REUSED).copy(reusedAcross = 2).detail()).isEqualTo("Password is also used in 1 other item")
        assertThat(finding(AuditIssue.REUSED).copy(reusedAcross = 4).detail()).isEqualTo("Password is also used in 3 other items")
        assertThat(finding(AuditIssue.WEAK).detail()).isEqualTo("Password is easy to guess")
        assertThat(finding(AuditIssue.STALE).copy(daysUnchanged = 400).detail()).isEqualTo("Password unchanged for 1 year")
        assertThat(finding(AuditIssue.STALE).copy(daysUnchanged = 800).detail()).isEqualTo("Password unchanged for 2 years")
        assertThat(finding(AuditIssue.INSECURE_URL).copy(url = "http://a.example").detail())
            .isEqualTo("http://a.example uses http, not https")
        assertThat(finding(AuditIssue.MISSING_2FA).copy(fieldUid = null, fieldLabel = null).detail())
            .isEqualTo("No one-time code saved for this site")
    }
}
