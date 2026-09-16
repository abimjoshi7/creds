package dev.creds.vault.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import dev.creds.vault.core.data.db.entity.FieldEntity
import dev.creds.vault.core.data.db.entity.FieldHistoryEntity
import dev.creds.vault.core.data.db.entity.ItemEntity
import dev.creds.vault.core.model.Template
import kotlinx.coroutines.flow.Flow

/**
 * Item rows.
 *
 * Every DAO in this module is `internal`: the FTS index is maintained by the repository
 * inside the same transaction as the write, so a caller reaching past the repository to
 * a DAO would silently desynchronise search. Repositories are the module's only public
 * write path.
 */
@Dao
internal interface ItemDao {

    @Upsert
    suspend fun upsert(item: ItemEntity)

    @Delete
    suspend fun delete(item: ItemEntity)

    @Query("SELECT * FROM items WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): ItemEntity?

    @Query("SELECT * FROM items WHERE uuid IN (:uuids)")
    suspend fun byUuids(uuids: List<String>): List<ItemEntity>

    @Query(
        """
        SELECT * FROM items
        WHERE trashed = 0 AND archived = 0
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeActive(): Flow<List<ItemEntity>>

    @Query(
        """
        SELECT * FROM items
        WHERE trashed = 0 AND archived = 0 AND favorite = 1
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeFavorites(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE trashed = 1 ORDER BY updated_at DESC")
    fun observeTrash(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE archived = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeArchive(): Flow<List<ItemEntity>>

    @Query(
        """
        SELECT * FROM items
        WHERE trashed = 0 AND archived = 0 AND template = :template
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeByTemplate(template: Template): Flow<List<ItemEntity>>

    /** Soft delete. The row stays so a sync layer can propagate the tombstone. */
    @Query("UPDATE items SET trashed = 1, updated_at = :now WHERE uuid = :uuid")
    suspend fun trash(uuid: String, now: Long)

    @Query("UPDATE items SET trashed = 0, updated_at = :now WHERE uuid = :uuid")
    suspend fun restore(uuid: String, now: Long)

    /** Hard delete, for emptying the trash. Cascades to fields, tags and associations. */
    @Query("DELETE FROM items WHERE uuid = :uuid")
    suspend fun purge(uuid: String)

    @Query("SELECT COUNT(*) FROM items WHERE trashed = 0")
    suspend fun countActive(): Int
}

@Dao
internal interface FieldDao {

    @Upsert
    suspend fun upsert(field: FieldEntity): Long

    @Insert
    suspend fun insertAll(fields: List<FieldEntity>): List<Long>

    @Query("DELETE FROM fields WHERE item_uuid = :itemUuid")
    suspend fun deleteForItem(itemUuid: String)

    @Query("SELECT * FROM fields WHERE item_uuid = :itemUuid AND deleted = 0 ORDER BY ord ASC")
    suspend fun forItem(itemUuid: String): List<FieldEntity>

    @Query("SELECT * FROM fields WHERE item_uuid IN (:itemUuids) AND deleted = 0 ORDER BY ord ASC")
    suspend fun forItems(itemUuids: List<String>): List<FieldEntity>

    @Query("SELECT * FROM fields WHERE uid = :uid")
    suspend fun byUid(uid: Long): FieldEntity?

    /**
     * Reuse detection: fingerprints shared by more than one item.
     *
     * Grouped by [FieldEntity.reuseHmac] with no decryption at all — the entire audit
     * runs against a keyed hash. Counting distinct items rather than rows matters
     * because one item legitimately holding the same value twice is not reuse.
     */
    @Query(
        """
        SELECT reuse_hmac FROM fields
        WHERE deleted = 0 AND reuse_hmac IS NOT NULL
        GROUP BY reuse_hmac
        HAVING COUNT(DISTINCT item_uuid) > 1
        """,
    )
    suspend fun reusedFingerprints(): List<ByteArray>

    @Query(
        """
        SELECT * FROM fields
        WHERE deleted = 0 AND reuse_hmac IN (:fingerprints)
        """,
    )
    suspend fun byFingerprints(fingerprints: List<ByteArray>): List<FieldEntity>

    /** Breach lookup candidates, without decrypting anything. */
    @Query("SELECT * FROM fields WHERE deleted = 0 AND sha1_prefix = :prefix")
    suspend fun bySha1Prefix(prefix: String): List<FieldEntity>
}

@Dao
internal interface FieldHistoryDao {

    @Insert
    suspend fun insert(entry: FieldHistoryEntity)

    @Query("SELECT * FROM field_history WHERE field_uid = :fieldUid ORDER BY replaced_at DESC")
    suspend fun forField(fieldUid: Long): List<FieldHistoryEntity>

    @Query("DELETE FROM field_history WHERE field_uid = :fieldUid")
    suspend fun deleteForField(fieldUid: Long)
}
