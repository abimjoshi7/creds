package dev.creds.vault.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.creds.vault.core.data.db.entity.AuditScoreEntity
import dev.creds.vault.core.data.db.entity.ItemAssociationEntity

@Dao
internal interface AuditDao {

    @Upsert
    suspend fun upsert(score: AuditScoreEntity)

    @Upsert
    suspend fun upsertAll(scores: List<AuditScoreEntity>)

    @Query("SELECT * FROM audit_scores WHERE field_uid = :fieldUid")
    suspend fun byField(fieldUid: Long): AuditScoreEntity?

    /**
     * Fields scoring at or below the weak threshold.
     *
     * The threshold is passed in rather than hardcoded so `:core:domain` stays the one
     * place that defines what "weak" means.
     */
    @Query(
        """
        SELECT audit_scores.* FROM audit_scores
        INNER JOIN fields ON fields.uid = audit_scores.field_uid
        WHERE fields.deleted = 0 AND audit_scores.score <= :threshold
        """,
    )
    suspend fun weakerThan(threshold: Int): List<AuditScoreEntity>

    @Query("DELETE FROM audit_scores WHERE field_uid = :fieldUid")
    suspend fun delete(fieldUid: Long)

    /** Invalidates every cached score, for a zxcvbn version bump. */
    @Query("DELETE FROM audit_scores")
    suspend fun clear()
}

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
}
