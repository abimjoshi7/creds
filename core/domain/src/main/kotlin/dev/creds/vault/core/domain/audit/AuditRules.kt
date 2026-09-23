package dev.creds.vault.core.domain.audit

import dev.creds.vault.core.domain.strength.WEAK_SCORE_THRESHOLD
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import java.net.URI
import kotlin.math.roundToInt

/** What the audit can find. [weight] is what one finding costs an item's health. */
enum class AuditIssue(val weight: Int, val fix: FixAction) {
    BREACHED(weight = 100, fix = FixAction.CHANGE_PASSWORD),
    REUSED(weight = 60, fix = FixAction.CHANGE_PASSWORD),
    WEAK(weight = 50, fix = FixAction.CHANGE_PASSWORD),
    STALE(weight = 15, fix = FixAction.CHANGE_PASSWORD),
    INSECURE_URL(weight = 15, fix = FixAction.USE_HTTPS),
    MISSING_2FA(weight = 10, fix = FixAction.ADD_TOTP),
}

enum class FixAction { CHANGE_PASSWORD, USE_HTTPS, ADD_TOTP }

/**
 * One password field as the audit sees it. No value: everything here was derived without
 * decrypting at audit time.
 *
 * @param score zxcvbn 0..4, or null while it has not been scored since its last change.
 * @param breached a hit in the offline list, or a non-zero online count.
 * @param breachCount times seen by Have I Been Pwned, or null if never checked online.
 * @param reusedAcross how many items, this one included, hold the same value. 1 is fine.
 */
data class AuditedPassword(
    val fieldUid: Long,
    val label: String,
    val score: Int?,
    val breached: Boolean,
    val breachCount: Int?,
    val reusedAcross: Int,
    val valueUpdatedAt: Long,
)

data class AuditedUrl(val fieldUid: Long, val label: String, val value: String)

data class AuditedItem(
    val uuid: String,
    val title: String,
    val template: Template,
    val passwords: List<AuditedPassword>,
    val urls: List<AuditedUrl>,
    val hasTotp: Boolean,
)

/**
 * One problem and where it is.
 *
 * Only the detail relevant to [issue] is set: [reusedAcross] for reuse, [breachCount] for
 * an online breach hit, [daysUnchanged] for staleness, [url] for an insecure URL.
 */
data class AuditFinding(
    val issue: AuditIssue,
    val itemUuid: String,
    val itemTitle: String,
    val fieldUid: Long?,
    val fieldLabel: String?,
    val reusedAcross: Int = 0,
    val breachCount: Int? = null,
    val daysUnchanged: Int = 0,
    val url: String? = null,
) {
    val fix: FixAction get() = issue.fix
}

data class AuditReport(
    val findings: List<AuditFinding>,
    /** 0..100, or null when nothing in the vault can be audited. */
    val healthScore: Int?,
    val auditedItems: Int,
    /** Password fields not yet scored since they last changed. Weak findings lag these. */
    val pendingScores: Int,
) {
    fun count(issue: AuditIssue): Int = findings.count { it.issue == issue }

    companion object {
        val EMPTY = AuditReport(emptyList(), healthScore = null, auditedItems = 0, pendingScores = 0)
    }
}

/**
 * The audit's rules, applied to a snapshot of the vault.
 *
 * Pure, so every threshold and every exemption is pinned by host tests. Gathering the
 * snapshot — scores, reuse groups, breach flags — is the data layer's job.
 */
object AuditRules {

    /**
     * The field types audited as passwords.
     *
     * PINs are left out on purpose. A four-digit card PIN is weak by definition, appears in
     * every breach list and collides across cards by chance, so auditing one as a password
     * would flag every card in the vault and teach people to ignore the audit. PINs get
     * their own check later (the spec's deferred "trivial PINs").
     */
    val PASSWORD_TYPES: Set<FieldType> = setOf(FieldType.PASSWORD, FieldType.CARD_TXN_PASSWORD)

    const val STALE_AFTER_DAYS: Int = 365

    private const val DAY_MS: Long = 24L * 60 * 60 * 1000

    fun evaluate(items: List<AuditedItem>, now: Long): AuditReport {
        val findings = mutableListOf<AuditFinding>()
        val itemScores = mutableListOf<Int>()
        var pending = 0

        for (item in items) {
            val itemFindings = findingsFor(item, now)
            pending += item.passwords.count { it.score == null }
            findings += itemFindings

            if (item.passwords.isNotEmpty() || item.urls.isNotEmpty()) {
                // Each kind of problem costs an item once, however many fields have it.
                val penalty = itemFindings.map { it.issue }.toSet().sumOf { it.weight }
                itemScores += (100 - penalty).coerceAtLeast(0)
            }
        }

        return AuditReport(
            findings = findings.sortedWith(compareBy({ it.issue.ordinal }, { it.itemTitle.lowercase() })),
            healthScore = if (itemScores.isEmpty()) null else itemScores.average().roundToInt(),
            auditedItems = itemScores.size,
            pendingScores = pending,
        )
    }

    private fun findingsFor(item: AuditedItem, now: Long): List<AuditFinding> = buildList {
        fun finding(issue: AuditIssue, uid: Long?, label: String?) =
            AuditFinding(issue, item.uuid, item.title, uid, label)

        for (password in item.passwords) {
            if (password.breached) {
                add(finding(AuditIssue.BREACHED, password.fieldUid, password.label).copy(breachCount = password.breachCount))
            }
            if (password.reusedAcross > 1) {
                add(finding(AuditIssue.REUSED, password.fieldUid, password.label).copy(reusedAcross = password.reusedAcross))
            }
            if (password.score != null && password.score <= WEAK_SCORE_THRESHOLD) {
                add(finding(AuditIssue.WEAK, password.fieldUid, password.label))
            }
            // Zero means "unknown", not 1970: never report a password as 56 years old.
            if (password.valueUpdatedAt > 0) {
                val days = ((now - password.valueUpdatedAt) / DAY_MS).toInt()
                if (days > STALE_AFTER_DAYS) {
                    add(finding(AuditIssue.STALE, password.fieldUid, password.label).copy(daysUnchanged = days))
                }
            }
        }

        item.urls.filter { isInsecure(it.value) }.forEach { url ->
            add(finding(AuditIssue.INSECURE_URL, url.fieldUid, url.label).copy(url = url.value.trim()))
        }

        if (item.template == Template.LOGIN && item.urls.any { it.value.isNotBlank() } && !item.hasTotp) {
            add(finding(AuditIssue.MISSING_2FA, null, null))
        }
    }

    /**
     * `http://` to a host on the public internet.
     *
     * Local addresses are exempt: a router admin page or a dev server has no certificate
     * to offer, and "use https" is not a fix anyone can apply to it.
     */
    fun isInsecure(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return false
        val host = runCatching { URI(trimmed).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return true
        return !isLocalHost(host)
    }

    /** The https form of an insecure URL: only the scheme changes. */
    fun httpsVersion(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true)) "https://" + trimmed.substring(7) else trimmed
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal") || host.endsWith(".home.arpa")) {
            return true
        }
        if (host == "[::1]" || host == "::1") return true
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 127 ||
            octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254)
    }
}
