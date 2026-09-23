package dev.creds.vault.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.creds.vault.core.data.db.dao.AssociationDao
import dev.creds.vault.core.data.db.dao.AttachmentDao
import dev.creds.vault.core.data.db.dao.AuditDao
import dev.creds.vault.core.data.db.dao.FieldDao
import dev.creds.vault.core.data.db.dao.FieldHistoryDao
import dev.creds.vault.core.data.db.dao.GeneratorHistoryDao
import dev.creds.vault.core.data.db.dao.ItemDao
import dev.creds.vault.core.data.db.dao.SearchDao
import dev.creds.vault.core.data.db.dao.TagDao
import dev.creds.vault.core.data.db.entity.AttachmentEntity
import dev.creds.vault.core.data.db.entity.AuditScoreEntity
import dev.creds.vault.core.data.db.entity.FieldEntity
import dev.creds.vault.core.data.db.entity.FieldHistoryEntity
import dev.creds.vault.core.data.db.entity.GeneratedValueEntity
import dev.creds.vault.core.data.db.entity.ItemAssociationEntity
import dev.creds.vault.core.data.db.entity.ItemEntity
import dev.creds.vault.core.data.db.entity.ItemTagCrossRef
import dev.creds.vault.core.data.db.entity.TagEntity

/**
 * The vault database.
 *
 * Every byte of this file is encrypted by SQLCipher with a key derived from the vault
 * key, and sensitive field values carry a second AES-GCM layer *inside* it. That inner
 * layer is what makes a decrypted page, a stale WAL frame, or a heap dump uninteresting.
 *
 * Schemas are exported to `core/data/schemas` so every future migration is reviewable as
 * a diff rather than as a hash that changed.
 */
@Database(
    entities = [
        ItemEntity::class,
        FieldEntity::class,
        FieldHistoryEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        ItemAssociationEntity::class,
        AuditScoreEntity::class,
        GeneratedValueEntity::class,
        AttachmentEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        // 2: generator_history. A new table only, which Room can derive from the two
        // exported schemas; nothing existing is touched.
        AutoMigration(from = 1, to = 2),
        // 3: breach columns on audit_scores, all defaulted or nullable. Existing scores
        // keep their values and read as "not breached, never checked online".
        AutoMigration(from = 2, to = 3),
        // 4: the attachments table. New table only; file bytes live outside the database.
        AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(Converters::class)
internal abstract class CredsDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun fieldDao(): FieldDao
    abstract fun fieldHistoryDao(): FieldHistoryDao
    abstract fun tagDao(): TagDao
    abstract fun searchDao(): SearchDao
    abstract fun auditDao(): AuditDao
    abstract fun associationDao(): AssociationDao
    abstract fun generatorHistoryDao(): GeneratorHistoryDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val NAME: String = "creds.db"
    }
}

/**
 * The FTS5 index Room cannot declare.
 *
 * Room ships `@Fts3` and `@Fts4` only, so this table is created by hand. It lives
 * outside Room's schema, which is why [SearchDao] skips query verification — Room
 * tolerates tables it does not know about, it just will not validate queries against
 * them.
 *
 * `content` is unstructured: title, subtitle, note and every non-sensitive field value
 * for one item, concatenated. Sensitive values never reach it — that is enforced at the
 * point the row is built, not here.
 *
 * `remove_diacritics 2` is the correct setting rather than the default `1`: it handles
 * codepoints outside Latin-1, so searching `jose` finds `José` and searching `uber`
 * finds `Über`.
 */
internal object FtsSchema {

    const val TABLE: String = "items_fts"

    const val CREATE: String = """
        CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(
            item_uuid UNINDEXED,
            content,
            tokenize = 'unicode61 remove_diacritics 2'
        )
    """

    const val DROP: String = "DROP TABLE IF EXISTS items_fts"

    /**
     * Creates the index on first open.
     *
     * This runs as part of Room's `onCreate`, inside the transaction that created the
     * rest of the schema, so a failure here rolls the whole database creation back
     * rather than leaving a vault with no search.
     */
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE)
        }
    }
}
