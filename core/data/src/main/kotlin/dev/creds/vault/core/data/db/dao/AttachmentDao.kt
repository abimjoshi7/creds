package dev.creds.vault.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.creds.vault.core.data.db.entity.AttachmentEntity

@Dao
internal interface AttachmentDao {

    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE item_uuid = :itemUuid AND deleted = 0 ORDER BY created_at ASC")
    suspend fun forItem(itemUuid: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id AND deleted = 0")
    suspend fun byId(id: String): AttachmentEntity?

    /** Ids whose bytes must exist on disk. Tombstones have none. */
    @Query("SELECT id FROM attachments WHERE deleted = 0")
    suspend fun liveIds(): List<String>

    /** Live attachments of the given items, so their files can go once the rows do. */
    @Query("SELECT id FROM attachments WHERE item_uuid IN (:itemUuids) AND deleted = 0")
    suspend fun liveIdsForItems(itemUuids: List<String>): List<String>

    @Query("UPDATE attachments SET deleted = 1, updated_at = :now WHERE id = :id AND item_uuid = :itemUuid")
    suspend fun markDeleted(itemUuid: String, id: String, now: Long): Int
}
