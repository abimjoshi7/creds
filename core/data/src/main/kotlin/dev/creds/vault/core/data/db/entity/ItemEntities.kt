package dev.creds.vault.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template

/**
 * An item row.
 *
 * `uuid` is the primary key rather than an autoincrement id because the spec's storage
 * model wants records that a sync layer can reconcile later without a data migration.
 * A device-local rowid would collide across devices; a uuid will not.
 *
 * [title] and [subtitle] are plaintext *within the encrypted database* — they are what
 * the list screen renders and what search matches on. [noteEnc] carries its own AES-GCM
 * layer on top, because a note is free text where people paste recovery codes.
 */
@Entity(
    tableName = "items",
    indices = [
        Index("template"),
        Index("updated_at"),
        Index(value = ["trashed", "archived"]),
    ],
)
internal data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "template")
    val template: Template,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String = "",

    /** AES-GCM sealed with the item's field key, AAD `uuid|note`. Null when empty. */
    @ColumnInfo(name = "note_enc", typeAffinity = ColumnInfo.BLOB)
    val noteEnc: ByteArray? = null,

    @ColumnInfo(name = "icon")
    val icon: String? = null,

    @ColumnInfo(name = "favorite")
    val favorite: Boolean = false,

    @ColumnInfo(name = "archived")
    val archived: Boolean = false,

    /** Soft-delete tombstone. Rows are never hard-deleted by the UI. */
    @ColumnInfo(name = "trashed")
    val trashed: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    // A data class holding a ByteArray compares it by identity, which silently breaks
    // equality in tests and in any set or cache. Same reasoning as SealedVaultKey.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItemEntity) return false
        return uuid == other.uuid &&
            template == other.template &&
            title == other.title &&
            subtitle == other.subtitle &&
            noteEnc.contentEqualsOrBothNull(other.noteEnc) &&
            icon == other.icon &&
            favorite == other.favorite &&
            archived == other.archived &&
            trashed == other.trashed &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + template.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + subtitle.hashCode()
        result = 31 * result + (noteEnc?.contentHashCode() ?: 0)
        result = 31 * result + (icon?.hashCode() ?: 0)
        result = 31 * result + favorite.hashCode()
        result = 31 * result + archived.hashCode()
        result = 31 * result + trashed.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * One field of an item.
 *
 * The derived columns are the whole reason search and audit can work without decrypting
 * anything:
 * - [searchText] holds the value only when the field is not sensitive. Secrets are never
 *   indexed, so an FTS match can never leak one.
 * - [reuseHmac] lets reuse detection be a `GROUP BY` over a keyed hash. Keyed off the
 *   vault key, so the fingerprints are meaningless without the vault open.
 * - [sha1Prefix] is the five hex characters HIBP's k-anonymity API takes.
 *
 * [valueUpdatedAt] is tracked separately from [updatedAt] because the staleness audit
 * asks "how long since this password changed", and relabelling a field must not reset
 * that clock.
 */
@Entity(
    tableName = "fields",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["item_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("item_uuid"),
        Index("reuse_hmac"),
        Index("sha1_prefix"),
        Index("type"),
    ],
)
internal data class FieldEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "uid")
    val uid: Long = 0,

    @ColumnInfo(name = "item_uuid")
    val itemUuid: String,

    @ColumnInfo(name = "type")
    val type: FieldType,

    @ColumnInfo(name = "label")
    val label: String,

    /** AES-GCM sealed with the item's field key, AAD `itemUuid|type.id`. */
    @ColumnInfo(name = "value_enc", typeAffinity = ColumnInfo.BLOB)
    val valueEnc: ByteArray?,

    @ColumnInfo(name = "sensitive")
    val sensitive: Boolean,

    @ColumnInfo(name = "ord")
    val ord: Int = 0,

    @ColumnInfo(name = "deleted")
    val deleted: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,

    @ColumnInfo(name = "value_updated_at")
    val valueUpdatedAt: Long = 0,

    /** Plaintext for non-sensitive fields, NULL for secrets. Never both. */
    @ColumnInfo(name = "search_text")
    val searchText: String? = null,

    @ColumnInfo(name = "reuse_hmac", typeAffinity = ColumnInfo.BLOB)
    val reuseHmac: ByteArray? = null,

    @ColumnInfo(name = "sha1_prefix")
    val sha1Prefix: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FieldEntity) return false
        return uid == other.uid &&
            itemUuid == other.itemUuid &&
            type == other.type &&
            label == other.label &&
            valueEnc.contentEqualsOrBothNull(other.valueEnc) &&
            sensitive == other.sensitive &&
            ord == other.ord &&
            deleted == other.deleted &&
            updatedAt == other.updatedAt &&
            valueUpdatedAt == other.valueUpdatedAt &&
            searchText == other.searchText &&
            reuseHmac.contentEqualsOrBothNull(other.reuseHmac) &&
            sha1Prefix == other.sha1Prefix
    }

    override fun hashCode(): Int {
        var result = uid.hashCode()
        result = 31 * result + itemUuid.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + (valueEnc?.contentHashCode() ?: 0)
        result = 31 * result + sensitive.hashCode()
        result = 31 * result + ord
        result = 31 * result + deleted.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + valueUpdatedAt.hashCode()
        result = 31 * result + (searchText?.hashCode() ?: 0)
        result = 31 * result + (reuseHmac?.contentHashCode() ?: 0)
        result = 31 * result + (sha1Prefix?.hashCode() ?: 0)
        return result
    }
}

/**
 * Previous values of a field.
 *
 * Kept encrypted and never indexed. History exists so "the site made me change it and
 * now the new one does not work" is recoverable; it is not a search surface.
 */
@Entity(
    tableName = "field_history",
    foreignKeys = [
        ForeignKey(
            entity = FieldEntity::class,
            parentColumns = ["uid"],
            childColumns = ["field_uid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("field_uid")],
)
internal data class FieldHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "field_uid")
    val fieldUid: Long,

    @ColumnInfo(name = "value_enc", typeAffinity = ColumnInfo.BLOB)
    val valueEnc: ByteArray,

    @ColumnInfo(name = "replaced_at")
    val replacedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FieldHistoryEntity) return false
        return id == other.id &&
            fieldUid == other.fieldUid &&
            valueEnc.contentEquals(other.valueEnc) &&
            replacedAt == other.replacedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fieldUid.hashCode()
        result = 31 * result + valueEnc.contentHashCode()
        result = 31 * result + replacedAt.hashCode()
        return result
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)
