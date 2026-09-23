package dev.creds.vault.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import dev.creds.vault.core.data.db.entity.AuditScoreEntity
import dev.creds.vault.core.data.db.entity.FieldEntity
import dev.creds.vault.core.data.db.entity.ItemAssociationEntity
import dev.creds.vault.core.data.db.entity.ItemEntity
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AuditDao {

    @Upsert
    suspend fun upsert(score: AuditScoreEntity)

    @Upsert
    suspend fun upsertAll(scores: List<AuditScoreEntity>)

    @Query("SELECT * FROM audit_scores WHERE field_uid = :fieldUid")
    suspend fun byField(fieldUid: Long): AuditScoreEntity?

    /**
     * Password fields whose score is missing, or older than their value.
     *
     * Empty values are skipped: an unset password is not a weak one, and the report
     * leaves it out rather than scoring nothing.
     */
    @Query(
        """
        SELECT f.* FROM fields f
        INNER JOIN items i ON i.uuid = f.item_uuid
        LEFT JOIN audit_scores s ON s.field_uid = f.uid
        WHERE f.deleted = 0 AND i.trashed = 0 AND f.type IN (:types) AND f.value_enc IS NOT NULL
          AND (s.field_uid IS NULL OR s.checked_at < f.value_updated_at)
        """,
    )
    suspend fun fieldsNeedingScore(types: List<String>): List<FieldEntity>

    /** Scored password fields not yet checked online since their value last changed. */
    @Query(
        """
        SELECT f.* FROM fields f
        INNER JOIN items i ON i.uuid = f.item_uuid
        INNER JOIN audit_scores s ON s.field_uid = f.uid
        WHERE f.deleted = 0 AND i.trashed = 0 AND f.type IN (:types) AND f.value_enc IS NOT NULL
          AND s.checked_at >= f.value_updated_at
          AND (s.online_checked_at IS NULL OR s.online_checked_at < f.value_updated_at)
        """,
    )
    suspend fun fieldsNeedingOnlineCheck(types: List<String>): List<FieldEntity>

    /** An online result never clears an offline hit: either source is enough. */
    @Query(
        """
        UPDATE audit_scores
        SET breach_count = :count, breached = (breached OR :count > 0), online_checked_at = :checkedAt
        WHERE field_uid = :fieldUid
        """,
    )
    suspend fun recordOnlineCheck(fieldUid: Long, count: Int, checkedAt: Long)

    /** Everything the report needs about passwords, websites and one-time codes. */
    @Query(
        """
        SELECT i.uuid AS item_uuid, i.title AS title, i.template AS template,
               f.uid AS uid, f.type AS type, f.label AS label,
               f.value_updated_at AS value_updated_at, f.search_text AS search_text,
               (f.value_enc IS NOT NULL) AS has_value,
               CASE WHEN s.checked_at >= f.value_updated_at THEN s.score END AS score,
               CASE WHEN s.checked_at >= f.value_updated_at THEN s.breached END AS breached,
               CASE WHEN s.online_checked_at >= f.value_updated_at THEN s.breach_count END AS breach_count
        FROM items i
        INNER JOIN fields f ON f.item_uuid = i.uuid
        LEFT JOIN audit_scores s ON s.field_uid = f.uid
        WHERE i.trashed = 0 AND i.archived = 0 AND f.deleted = 0 AND f.type IN (:types)
        ORDER BY i.uuid, f.ord
        """,
    )
    suspend fun reportRows(types: List<String>): List<AuditFieldRow>

    /** Password fields sharing a value with another item, and how many items share it. */
    @Query(
        """
        SELECT f.uid AS uid, g.items AS items FROM fields f
        INNER JOIN items i ON i.uuid = f.item_uuid
        INNER JOIN (
            SELECT f2.reuse_hmac AS hmac, COUNT(DISTINCT f2.item_uuid) AS items FROM fields f2
            INNER JOIN items i2 ON i2.uuid = f2.item_uuid
            WHERE f2.deleted = 0 AND i2.trashed = 0 AND f2.type IN (:types) AND f2.reuse_hmac IS NOT NULL
            GROUP BY f2.reuse_hmac
            HAVING COUNT(DISTINCT f2.item_uuid) > 1
        ) g ON g.hmac = f.reuse_hmac
        WHERE f.deleted = 0 AND i.trashed = 0 AND f.type IN (:types)
        """,
    )
    suspend fun reusedFields(types: List<String>): List<ReuseRow>

    /**
     * Emits whenever anything the audit reads may have changed.
     *
     * The value is meaningless; the query exists so Room watches `items`, `fields` and
     * `audit_scores` together.
     */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM audit_scores) + (SELECT COUNT(*) FROM fields)
             + COALESCE((SELECT MAX(updated_at) FROM items), 0)
        """,
    )
    fun observeInputs(): Flow<Long>

    @RawQuery(observedEntities = [ItemEntity::class, FieldEntity::class, AuditScoreEntity::class])
    fun observeCounts(query: SupportSQLiteQuery): Flow<AuditCounts>

    @Query("DELETE FROM audit_scores WHERE field_uid = :fieldUid")
    suspend fun delete(fieldUid: Long)

    /** Invalidates every cached result, for a scorer or breach-list change. */
    @Query("DELETE FROM audit_scores")
    suspend fun clear()
}

internal data class AuditFieldRow(
    @ColumnInfo(name = "item_uuid") val itemUuid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "template") val template: Template,
    @ColumnInfo(name = "uid") val uid: Long,
    @ColumnInfo(name = "type") val type: FieldType,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "value_updated_at") val valueUpdatedAt: Long,
    @ColumnInfo(name = "search_text") val searchText: String?,
    @ColumnInfo(name = "has_value") val hasValue: Boolean,
    @ColumnInfo(name = "score") val score: Int?,
    @ColumnInfo(name = "breached") val breached: Boolean?,
    @ColumnInfo(name = "breach_count") val breachCount: Int?,
)

internal data class ReuseRow(
    @ColumnInfo(name = "uid") val uid: Long,
    @ColumnInfo(name = "items") val items: Int,
)

internal data class AuditCounts(
    @ColumnInfo(name = "weak") val weak: Int,
    @ColumnInfo(name = "reused") val reused: Int,
    @ColumnInfo(name = "breached") val breached: Int,
)

@Dao
internal interface AssociationDao {

    @Upsert
    suspend fun upsert(association: ItemAssociationEntity)

    @Query("SELECT * FROM item_associations WHERE item_uuid = :itemUuid")
    suspend fun forItem(itemUuid: String): List<ItemAssociationEntity>

    /**
     * Autofill's lookup.
     *
     * Matching on kind and value alone is deliberately *not* enough to fill — the caller
     * still has to compare the signing certificate hash for package associations. This
     * returns candidates, not authorisation.
     */
    @Query("SELECT * FROM item_associations WHERE kind = :kind AND value = :value")
    suspend fun byTarget(kind: String, value: String): List<ItemAssociationEntity>

    @Query("DELETE FROM item_associations WHERE item_uuid = :itemUuid AND kind = :kind AND value = :value")
    suspend fun delete(itemUuid: String, kind: String, value: String)

    @Query("SELECT * FROM item_associations")
    suspend fun all(): List<ItemAssociationEntity>

    /** Everything autofill needs to match live items, with nothing decrypted. */
    @Query("SELECT uuid, title, subtitle, template FROM items WHERE trashed = 0 AND archived = 0 ORDER BY title COLLATE NOCASE")
    suspend fun autofillItems(): List<AutofillItemRow>

    /** Websites of non-sensitive URL fields, which are plaintext in `search_text`. */
    @Query("SELECT item_uuid, search_text FROM fields WHERE deleted = 0 AND type = 'url' AND search_text IS NOT NULL")
    suspend fun websites(): List<WebsiteRow>
}

internal data class AutofillItemRow(
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "subtitle") val subtitle: String,
    @ColumnInfo(name = "template") val template: Template,
)

internal data class WebsiteRow(
    @ColumnInfo(name = "item_uuid") val itemUuid: String,
    @ColumnInfo(name = "search_text") val value: String,
)
