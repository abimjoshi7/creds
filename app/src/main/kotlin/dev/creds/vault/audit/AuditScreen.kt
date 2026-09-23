package dev.creds.vault.audit

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.domain.audit.AuditFinding
import dev.creds.vault.core.domain.audit.AuditIssue
import dev.creds.vault.core.domain.audit.FixAction
import dev.creds.vault.core.ui.components.QuietPanel
import dev.creds.vault.core.ui.theme.SeverityCritical
import dev.creds.vault.core.ui.theme.SeverityHigh
import dev.creds.vault.core.ui.theme.SeverityMedium
import dev.creds.vault.core.ui.theme.SeverityOk

object AuditTags {
    const val SCORE = "audit:score"
    const val PROGRESS = "audit:progress"
    const val ONLINE = "audit:online"
    const val CHECK_NOW = "audit:check-now"
    const val ONLINE_ERROR = "audit:online-error"
    const val ALL_CLEAR = "audit:all-clear"
    const val NOTHING = "audit:nothing"
    fun issue(issue: AuditIssue) = "audit:issue:${issue.name}"
    fun finding(finding: AuditFinding) = "audit:finding:${finding.issue.name}:${finding.itemUuid}:${finding.fieldUid}"
    fun fix(finding: AuditFinding) = "${finding(finding)}:fix"
}

/** Everything the dashboard can ask for; defaults are no-ops for tests. */
data class AuditActions(
    val onBack: () -> Unit = {},
    val onSelectIssue: (AuditIssue?) -> Unit = {},
    val onOnlineCheck: (Boolean) -> Unit = {},
    val onCheckNow: () -> Unit = {},
    val onOpenItem: (uuid: String) -> Unit = {},
    val onEditItem: (uuid: String) -> Unit = {},
    val onUseHttps: (AuditFinding) -> Unit = {},
)

@Composable
fun AuditRoute(
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    onEditItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AuditScreen(
        state = state,
        actions = AuditActions(
            onBack = onBack,
            onSelectIssue = viewModel::selectIssue,
            onOnlineCheck = viewModel::setOnlineBreachCheck,
            onCheckNow = viewModel::checkNow,
            onOpenItem = onOpenItem,
            onEditItem = onEditItem,
            onUseHttps = viewModel::useHttps,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(
    state: AuditUiState,
    actions: AuditActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Password health") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val report = state.report
        if (report == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "score") { ScoreHeader(state) }

            item(key = "issues") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AuditIssue.entries.filter { report.count(it) > 0 }) { issue ->
                        FilterChip(
                            selected = state.selectedIssue == issue,
                            onClick = { actions.onSelectIssue(issue) },
                            label = { Text("${issue.title} · ${report.count(issue)}") },
                            leadingIcon = { Icon(issue.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag(AuditTags.issue(issue)),
                        )
                    }
                }
            }

            item(key = "online") { OnlineCheckPanel(state, actions) }

            when {
                report.auditedItems == 0 -> item(key = "nothing") {
                    Text(
                        "Nothing to check yet. Logins, bank accounts and other items with passwords appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(AuditTags.NOTHING),
                    )
                }

                report.findings.isEmpty() -> item(key = "clear") {
                    Text(
                        if (report.pendingScores > 0) "No problems found so far." else "No problems found.",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag(AuditTags.ALL_CLEAR),
                    )
                }
            }

            items(state.visibleFindings, key = AuditTags::finding) { finding ->
                FindingRow(finding, actions)
            }
        }
    }
}

@Composable
private fun ScoreHeader(state: AuditUiState) {
    val report = state.report ?: return
    val score = report.healthScore
    val busy = state.status.scoring || report.pendingScores > 0

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                score?.toString() ?: "—",
                style = MaterialTheme.typography.displayMedium,
                color = score?.let(::scoreColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AuditTags.SCORE),
            )
            if (score != null) {
                Text(
                    "/ 100 · ${scoreLabel(score)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        Text(
            if (report.auditedItems == 1) "Across 1 item" else "Across ${report.auditedItems} items",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            Column(Modifier.testTag(AuditTags.PROGRESS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (report.pendingScores > 0) "Checking ${report.pendingScores} passwords…" else "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OnlineCheckPanel(state: AuditUiState, actions: AuditActions) {
    val settings = state.settings
    QuietPanel {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = settings.onlineBreachCheck, role = Role.Switch, onValueChange = actions.onOnlineCheck)
                .testTag(AuditTags.ONLINE),
        ) {
            Text("Check online with Have I Been Pwned", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = settings.onlineBreachCheck, onCheckedChange = null)
        }
        Text(
            "Passwords are always checked on this device against a million known breached ones. " +
                "Online, only the first 5 characters of each password's SHA-1 hash are sent — never the password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.onlineBreachCheck) {
            val lastChecked = settings.lastOnlineCheckAt
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.status.checkingOnline -> "Checking…"
                        lastChecked != null -> "Last checked ${DateUtils.getRelativeTimeSpanString(lastChecked)}"
                        else -> "Not checked yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = actions.onCheckNow,
                    enabled = !state.status.checkingOnline,
                    modifier = Modifier.testTag(AuditTags.CHECK_NOW),
                ) { Text("Check now") }
            }
            state.status.onlineError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(AuditTags.ONLINE_ERROR),
                )
            }
        }
    }
}

@Composable
private fun FindingRow(finding: AuditFinding, actions: AuditActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.onOpenItem(finding.itemUuid) }
            .padding(vertical = 4.dp)
            .testTag(AuditTags.finding(finding)),
    ) {
        Icon(
            finding.issue.icon,
            contentDescription = finding.issue.title,
            tint = finding.issue.color,
            modifier = Modifier.padding(end = 16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(finding.itemTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                finding.detail(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = {
                when (finding.fix) {
                    FixAction.USE_HTTPS -> actions.onUseHttps(finding)
                    FixAction.CHANGE_PASSWORD, FixAction.ADD_TOTP -> actions.onEditItem(finding.itemUuid)
                }
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(start = 8.dp).testTag(AuditTags.fix(finding)),
        ) {
            Text(finding.fix.label)
        }
    }
}

/** One line on what is wrong, specific enough to act on without opening the item. */
internal fun AuditFinding.detail(): String {
    val field = fieldLabel ?: "Password"
    return when (issue) {
        AuditIssue.BREACHED -> when (val count = breachCount) {
            null, 0 -> "$field appears in a list of breached passwords"
            else -> "$field seen ${"%,d".format(count)} times in breaches"
        }
        AuditIssue.REUSED -> "$field is also used in ${reusedAcross - 1} other ${if (reusedAcross == 2) "item" else "items"}"
        AuditIssue.WEAK -> "$field is easy to guess"
        AuditIssue.STALE -> "$field unchanged for ${daysUnchanged / 365} ${if (daysUnchanged / 365 == 1) "year" else "years"}"
        AuditIssue.INSECURE_URL -> "${url ?: field} uses http, not https"
        AuditIssue.MISSING_2FA -> "No one-time code saved for this site"
    }
}

internal val AuditIssue.title: String
    get() = when (this) {
        AuditIssue.BREACHED -> "Breached"
        AuditIssue.REUSED -> "Reused"
        AuditIssue.WEAK -> "Weak"
        AuditIssue.STALE -> "Old"
        AuditIssue.INSECURE_URL -> "Insecure website"
        AuditIssue.MISSING_2FA -> "No 2FA"
    }

private val AuditIssue.icon: ImageVector
    get() = when (this) {
        AuditIssue.BREACHED -> Icons.Outlined.GppBad
        AuditIssue.REUSED -> Icons.Outlined.Repeat
        AuditIssue.WEAK -> Icons.Outlined.LockOpen
        AuditIssue.STALE -> Icons.Outlined.History
        AuditIssue.INSECURE_URL -> Icons.Outlined.Language
        AuditIssue.MISSING_2FA -> Icons.Outlined.PhonelinkLock
    }

// Severity colours, like the strength meter, stay out of the Material scheme so a theme
// can never make a breach look calm.
private val AuditIssue.color: Color
    get() = when (this) {
        AuditIssue.BREACHED -> SeverityCritical
        AuditIssue.REUSED, AuditIssue.WEAK -> SeverityHigh
        AuditIssue.STALE, AuditIssue.INSECURE_URL, AuditIssue.MISSING_2FA -> SeverityMedium
    }

private val FixAction.label: String
    get() = when (this) {
        FixAction.CHANGE_PASSWORD -> "Change"
        FixAction.USE_HTTPS -> "Use https"
        FixAction.ADD_TOTP -> "Add code"
    }

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> SeverityOk
    score >= 70 -> SeverityMedium
    score >= 40 -> SeverityHigh
    else -> SeverityCritical
}

private fun scoreLabel(score: Int): String = when {
    score >= 90 -> "Good"
    score >= 70 -> "Fair"
    score >= 40 -> "Needs work"
    else -> "Poor"
}
