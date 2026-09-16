package dev.creds.vault.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.creds.vault.core.data.db.entity.ItemTagCrossRef
import dev.creds.vault.core.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TagDao {

    @Upsert
    suspend fun upsert(tag: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun byName(name: String): TagEntity?

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Joins are replaced wholesale on save, so a repeated pair is expected rather than
     * exceptional — hence IGNORE instead of a failed transaction.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(crossRefs: List<ItemTagCrossRef>)

    @Query("DELETE FROM item_tags WHERE item_uuid = :itemUuid")
    suspend fun unlinkAll(itemUuid: String)

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN item_tags ON tags.id = item_tags.tag_id
        WHERE item_tags.item_uuid = :itemUuid
        ORDER BY tags.name COLLATE NOCASE ASC
        """,
    )
    suspend fun forItem(itemUuid: String): List<TagEntity>

    @Query("SELECT * FROM item_tags WHERE item_uuid IN (:itemUuids)")
    suspend fun crossRefsForItems(itemUuids: List<String>): List<ItemTagCrossRef>

    @Query("SELECT item_uuid FROM item_tags WHERE tag_id IN (:tagIds)")
    suspend fun itemUuidsWithTags(tagIds: List<Long>): List<String>
}
