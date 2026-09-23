package dev.creds.vault.audit

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.data.prefs.AuditSettings
import dev.creds.vault.core.domain.audit.AuditFinding
import dev.creds.vault.core.domain.audit.AuditIssue
import dev.creds.vault.core.domain.audit.AuditReport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The password health dashboard, rendered in-process against hand-built reports. */
@RunWith(AndroidJUnit4::class)
class AuditScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val weak = AuditFinding(AuditIssue.WEAK, "a", "Mail", fieldUid = 1, fieldLabel = "Password")
    private val insecure = AuditFinding(
        AuditIssue.INSECURE_URL, "b", "Forum", fieldUid = 2, fieldLabel = "Website", url = "http://forum.example",
    )
    private val missing2fa = AuditFinding(AuditIssue.MISSING_2FA, "c", "Shop", fieldUid = null, fieldLabel = null)
    private val report = AuditReport(listOf(weak, insecure, missing2fa), healthScore = 62, auditedItems = 3, pendingScores = 0)

    @Test
    fun showsTheScoreAndEveryFinding() {
        setContent(AuditUiState(report = report))

        compose.onNodeWithTag(AuditTags.SCORE).assertTextEquals("62")
        listOf(weak, insecure, missing2fa).forEach { compose.onNodeWithTag(AuditTags.finding(it)).assertIsDisplayed() }
        compose.onAllNodesWithTag(AuditTags.PROGRESS).assertCountEquals(0)
    }

    @Test
    fun selectingAnIssueFiltersTheList() {
        setContent(AuditUiState(report = report, selectedIssue = AuditIssue.WEAK))

        compose.onNodeWithTag(AuditTags.finding(weak)).assertIsDisplayed()
        compose.onAllNodesWithTag(AuditTags.finding(insecure)).assertCountEquals(0)
    }

    @Test
    fun issueChipsReportSelection() {
        var selected: AuditIssue? = null
        setContent(AuditUiState(report = report), AuditActions(onSelectIssue = { selected = it }))

        compose.onNodeWithTag(AuditTags.issue(AuditIssue.INSECURE_URL)).performClick()

        assertEquals(AuditIssue.INSECURE_URL, selected)
        compose.onAllNodesWithTag(AuditTags.issue(AuditIssue.BREACHED)).assertCountEquals(0)
    }

    @Test
    fun fixesRouteByKind() {
        var edited: String? = null
        var https: AuditFinding? = null
        var opened: String? = null
        setContent(
            AuditUiState(report = report),
            AuditActions(onEditItem = { edited = it }, onUseHttps = { https = it }, onOpenItem = { opened = it }),
        )

        compose.onNodeWithTag(AuditTags.fix(weak)).performClick()
        compose.onNodeWithTag(AuditTags.fix(insecure)).performClick()
        compose.onNodeWithTag(AuditTags.finding(missing2fa)).performClick()

        assertEquals("a", edited)
        assertEquals(insecure, https)
        assertEquals("c", opened)
    }

    @Test
    fun theOnlineCheckIsOffByDefaultAndOffersCheckNowOnlyWhenOn() {
        var toggled: Boolean? = null
        setContent(AuditUiState(report = report), AuditActions(onOnlineCheck = { toggled = it }))

        compose.onAllNodesWithTag(AuditTags.CHECK_NOW).assertCountEquals(0)
        compose.onNodeWithTag(AuditTags.ONLINE).performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun anOnlineErrorIsShown() {
        setContent(
            AuditUiState(
                report = report,
                settings = AuditSettings(onlineBreachCheck = true),
                status = AuditStatus(onlineError = "Couldn't reach Have I Been Pwned."),
            ),
        )

        compose.onNodeWithTag(AuditTags.CHECK_NOW).assertIsDisplayed()
        compose.onNodeWithTag(AuditTags.ONLINE_ERROR).assertIsDisplayed()
    }

    @Test
    fun pendingScoresShowProgressAndAnHonestAllClear() {
        setContent(AuditUiState(report = AuditReport(emptyList(), healthScore = 100, auditedItems = 2, pendingScores = 5)))

        compose.onNodeWithTag(AuditTags.PROGRESS).assertIsDisplayed()
        compose.onNodeWithTag(AuditTags.ALL_CLEAR).assertTextEquals("No problems found so far.")
    }

    @Test
    fun anEmptyVaultHasNoScore() {
        setContent(AuditUiState(report = AuditReport.EMPTY))

        compose.onNodeWithTag(AuditTags.SCORE).assertTextEquals("—")
        compose.onNodeWithTag(AuditTags.NOTHING).assertIsDisplayed()
    }

    private fun setContent(state: AuditUiState, actions: AuditActions = AuditActions()) {
        compose.setContent { AuditScreen(state = state, actions = actions) }
    }
}
