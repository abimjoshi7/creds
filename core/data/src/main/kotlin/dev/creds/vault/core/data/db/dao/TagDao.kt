package dev.creds.vault.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.creds.vault.core.data.db.entity.ItemTagCrossRef
import dev.creds.vault.core.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TagDao {

    @Insert
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun all(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: Long): TagEntity?

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

    /**
     * Every join row.
     *
     * The list screen resolves tags for its rows from this rather than with an
     * `IN (:uuids)` lookup per emission: the table is small, and a bound list the size of
     * the vault would run into SQLite's variable limit on a large one.
     */
    @Query("SELECT * FROM item_tags")
    fun observeCrossRefs(): Flow<List<ItemTagCrossRef>>

    /** Items per tag, counting only what the default list would show. */
    @Query(
        """
        SELECT item_tags.tag_id AS tagId, COUNT(*) AS count FROM item_tags
        INNER JOIN items ON items.uuid = item_tags.item_uuid
        WHERE items.trashed = 0 AND items.archived = 0
        GROUP BY item_tags.tag_id
        """,
    )
    fun observeTagCounts(): Flow<List<TagCount>>

    @Query("SELECT * FROM item_tags WHERE item_uuid IN (:itemUuids)")
    suspend fun crossRefsForItems(itemUuids: List<String>): List<ItemTagCrossRef>

    @Query("SELECT item_uuid FROM item_tags WHERE tag_id IN (:tagIds)")
    suspend fun itemUuidsWithTags(tagIds: List<Long>): List<String>
}

internal data class TagCount(
    val tagId: Long,
    val count: Int,
)
