package dev.creds.vault.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.creds.vault.core.data.db.entity.GeneratedValueEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface GeneratorHistoryDao {

    @Insert
    suspend fun insert(entry: GeneratedValueEntity): Long

    /** Newest first. Ties on the clock fall back to insertion order. */
    @Query("SELECT * FROM generator_history ORDER BY created_at DESC, id DESC")
    suspend fun all(): List<GeneratedValueEntity>

    /** Emits whenever the list changes. The rows themselves are read through [all]. */
    @Query("SELECT COUNT(*) FROM generator_history")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM generator_history WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** Keeps the newest [keep] rows and deletes the rest. */
    @Query(
        """
        DELETE FROM generator_history WHERE id NOT IN (
            SELECT id FROM generator_history ORDER BY created_at DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimTo(keep: Int): Int

    @Query("DELETE FROM generator_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM generator_history")
    suspend fun clear()
}
