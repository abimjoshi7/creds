package dev.creds.vault.core.data.repository

import androidx.room.withTransaction
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.entity.AuditScoreEntity
import dev.creds.vault.core.data.db.entity.FieldEntity
import dev.creds.vault.core.domain.audit.AuditReport
import dev.creds.vault.core.domain.audit.AuditRules
import dev.creds.vault.core.domain.audit.AuditedItem
import dev.creds.vault.core.domain.audit.AuditedPassword
import dev.creds.vault.core.domain.audit.AuditedUrl
import dev.creds.vault.core.domain.breach.BreachRangeSource
import dev.creds.vault.core.domain.breach.HibpRange
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import dev.creds.vault.core.model.FieldType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

/**
 * The vault audit's storage: scoring passwords, checking them for breaches, and reading
 * the results back as a report.
 *
 * Decryption happens only in [rescore] and [checkOnline], once per changed password.
 * Building the report decrypts nothing — scores and breach flags are cached, reuse is a
 * `GROUP BY` over keyed hashes, and website values are the plaintext `search_text` of
 * non-sensitive fields — so the dashboard can be re-rendered on every change for free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditRepository internal constructor(
    private val database: CredsDatabase,
    private val fieldCipher: FieldCipher,
) {

    private val auditDao = database.auditDao()
    private val passwordTypes = AuditRules.PASSWORD_TYPES.map { it.id }
    private val reportTypes = passwordTypes + FieldType.URL.id + FieldType.TOTP.id

    /**
     * Scores every password that has changed since it was last scored.
     *
     * Written in small transactions, so a lock part-way through keeps what was done and
     * the next unlock carries on from there. Returns how many were scored.
     */
    suspend fun rescore(
        vaultKey: VaultKey,
        estimator: PasswordStrengthEstimator,
        isBreachedOffline: (String) -> Boolean,
        now: Long,
    ): Int {
        val pending = auditDao.fieldsNeedingScore(passwordTypes)
        pending.chunked(BATCH).forEach { batch ->
            val scores = batch.map { field ->
                val value = open(vaultKey, field)
                val chars = value.toCharArray()
                val strength = try {
                    estimator.estimate(chars)
                } finally {
                    chars.wipe()
                }
                AuditScoreEntity(
                    fieldUid = field.uid,
                    score = strength.score,
                    guessesLog10 = strength.guessesLog10,
                    checkedAt = checkedAt(now, field),
                    breached = isBreachedOffline(value),
                )
            }
            database.withTransaction { auditDao.upsertAll(scores) }
        }
        return pending.size
    }

    /**
     * Checks scored passwords against Have I Been Pwned, one request per distinct
     * five-character hash prefix.
     *
     * Only the prefix leaves the device. Results are recorded prefix by prefix, so a
     * network failure part-way keeps what was learned; the failure itself is rethrown for
     * the caller to report.
     */
    suspend fun checkOnline(vaultKey: VaultKey, source: BreachRangeSource, now: Long): OnlineCheckResult {
        val pending = auditDao.fieldsNeedingOnlineCheck(passwordTypes)
        val byPrefix = pending
            .map { field -> field to HibpRange.sha1Hex(open(vaultKey, field)) }
            .groupBy { (_, hash) -> HibpRange.prefix(hash) }

        var breached = 0
        for ((prefix, fields) in byPrefix) {
            val body = source.range(prefix)
            database.withTransaction {
                fields.forEach { (field, hash) ->
                    val count = HibpRange.count(body, hash)
                    if (count > 0) breached++
                    auditDao.recordOnlineCheck(field.uid, count, checkedAt(now, field))
                }
            }
        }
        return OnlineCheckResult(checked = pending.size, breached = breached)
    }

    /**
     * Emits whenever anything the audit reads may have changed — an edit, a new item, a
     * score written. Carries no content.
     */
    fun observeChanges(): Flow<Long> = auditDao.observeInputs()

    /** The audit report, recomputed whenever the vault or its cached results change. */
    fun observeReport(clock: () -> Long): Flow<AuditReport> =
        auditDao.observeInputs().mapLatest { report(clock()) }

    suspend fun report(now: Long): AuditReport {
        val rows = auditDao.reportRows(reportTypes)
        val reuse = auditDao.reusedFields(passwordTypes).associate { it.uid to it.items }

        val items = rows.groupBy { it.itemUuid }.values.map { itemRows ->
            val first = itemRows.first()
            AuditedItem(
                uuid = first.itemUuid,
                title = first.title,
                template = first.template,
                passwords = itemRows
                    .filter { it.type in AuditRules.PASSWORD_TYPES && it.hasValue }
                    .map {
                        AuditedPassword(
                            fieldUid = it.uid,
                            label = it.label,
                            score = it.score,
                            breached = it.breached == true,
                            breachCount = it.breachCount,
                            reusedAcross = reuse[it.uid] ?: 1,
                            valueUpdatedAt = it.valueUpdatedAt,
                        )
                    },
                // A website marked sensitive has no plaintext to read without decrypting,
                // so it sits out the URL checks rather than forcing a decrypt here.
                urls = itemRows
                    .filter { it.type == FieldType.URL && !it.searchText.isNullOrBlank() }
                    .map { AuditedUrl(it.uid, it.label, it.searchText.orEmpty()) },
                hasTotp = itemRows.any { it.type == FieldType.TOTP && it.hasValue },
            )
        }
        return AuditRules.evaluate(items, now)
    }

    /** Forgets every score and breach result, so the next [rescore] redoes them all. */
    suspend fun clearResults() = auditDao.clear()

    private fun open(vaultKey: VaultKey, field: FieldEntity): String =
        fieldCipher.openValue(vaultKey, field.itemUuid, field.type, requireNotNull(field.valueEnc))

    /**
     * Never earlier than the value's own timestamp. A clock set back after an edit would
     * otherwise record a result that is stale on arrival, and the audit would rescore the
     * same field forever.
     */
    private fun checkedAt(now: Long, field: FieldEntity): Long = maxOf(now, field.valueUpdatedAt)

    private companion object {
        const val BATCH = 25
    }
}

data class OnlineCheckResult(val checked: Int, val breached: Int)
