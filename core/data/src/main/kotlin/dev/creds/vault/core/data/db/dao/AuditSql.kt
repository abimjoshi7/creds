package dev.creds.vault.core.data.db.dao

import dev.creds.vault.core.domain.audit.AuditRules
import dev.creds.vault.core.domain.strength.WEAK_SCORE_THRESHOLD

/**
 * The audit smart lists as SQL, shared by the item list and the drawer counts so the two
 * can never disagree about which items are weak, reused or breached.
 *
 * Each statement selects item uuids. A score only counts while it is current — checked
 * no earlier than the value last changed — so an edited password drops out of "weak" the
 * moment it is saved, not when the audit next runs. Trashed items never count, which
 * matters most for reuse: a password left in the trash is not a second copy in use.
 */
internal object AuditSql {

    private val types: List<String> = AuditRules.PASSWORD_TYPES.map { it.id }.sorted()
    private val typePlaceholders = types.joinToString(",") { "?" }

    val weakItems: SqlStatement = SqlStatement(
        """
        SELECT f.item_uuid FROM fields f
        INNER JOIN audit_scores s ON s.field_uid = f.uid
        WHERE f.deleted = 0 AND f.type IN ($typePlaceholders)
          AND s.checked_at >= f.value_updated_at AND s.score <= ?
        """.trimIndent(),
        types + WEAK_SCORE_THRESHOLD,
    )

    val breachedItems: SqlStatement = SqlStatement(
        """
        SELECT f.item_uuid FROM fields f
        INNER JOIN audit_scores s ON s.field_uid = f.uid
        WHERE f.deleted = 0 AND f.type IN ($typePlaceholders)
          AND s.checked_at >= f.value_updated_at AND s.breached = 1
        """.trimIndent(),
        types,
    )

    val reusedItems: SqlStatement = SqlStatement(
        """
        SELECT f.item_uuid FROM fields f
        WHERE f.deleted = 0 AND f.type IN ($typePlaceholders) AND f.reuse_hmac IN (
            SELECT f2.reuse_hmac FROM fields f2
            INNER JOIN items i2 ON i2.uuid = f2.item_uuid
            WHERE f2.deleted = 0 AND i2.trashed = 0 AND f2.type IN ($typePlaceholders)
              AND f2.reuse_hmac IS NOT NULL
            GROUP BY f2.reuse_hmac
            HAVING COUNT(DISTINCT f2.item_uuid) > 1
        )
        """.trimIndent(),
        types + types,
    )

    /** Item counts for the three lists, in the drawer's default scope. */
    val counts: SqlStatement = run {
        val scope = "trashed = 0 AND archived = 0"
        SqlStatement(
            """
            SELECT
                (SELECT COUNT(*) FROM items WHERE $scope AND uuid IN (${weakItems.sql})) AS weak,
                (SELECT COUNT(*) FROM items WHERE $scope AND uuid IN (${reusedItems.sql})) AS reused,
                (SELECT COUNT(*) FROM items WHERE $scope AND uuid IN (${breachedItems.sql})) AS breached
            """.trimIndent(),
            weakItems.args + reusedItems.args + breachedItems.args,
        )
    }
}
