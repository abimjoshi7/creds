package dev.creds.vault.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file attached to an item.
 *
 * The bytes are not here: they are sealed into their own file by `AttachmentStore`, named
 * by [id]. This row is what makes that file part of the vault — a file with no row is
 * garbage and is swept on unlock.
 *
 * [nameEnc] is sealed like a note and never indexed. [mimeType] and [size] stay plaintext
 * inside the encrypted database; the sealed file on disk gives the size away regardless.
 *
 * Removal leaves a tombstone ([deleted]) for a future sync layer, but the bytes are
 * destroyed immediately: a removed file should stop existing, not linger as ciphertext.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["item_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("item_uuid")],
)
internal data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "item_uuid")
    val itemUuid: String,

    /** AES-GCM sealed with the item's field key, AAD `itemUuid|attachment-name|id`. */
    @ColumnInfo(name = "name_enc", typeAffinity = ColumnInfo.BLOB)
    val nameEnc: ByteArray,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** Plaintext size in bytes. */
    @ColumnInfo(name = "size")
    val size: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted")
    val deleted: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachmentEntity) return false
        return id == other.id &&
            itemUuid == other.itemUuid &&
            nameEnc.contentEquals(other.nameEnc) &&
            mimeType == other.mimeType &&
            size == other.size &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            deleted == other.deleted
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + itemUuid.hashCode()
        result = 31 * result + nameEnc.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + deleted.hashCode()
        return result
    }
}
