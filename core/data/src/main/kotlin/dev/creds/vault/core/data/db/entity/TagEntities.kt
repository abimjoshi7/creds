package dev.creds.vault.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created label.
 *
 * Orthogonal to `Template`: a template says what an item *is* and is closed, a tag says
 * where the user *filed* it and is open. Renaming a tag changes one row here and nothing
 * about item structure, which is the point of keeping them separate.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
internal data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    /** ARGB, or null to let the theme pick. */
    @ColumnInfo(name = "color")
    val color: Int? = null,
)

/**
 * Many-to-many join between items and tags.
 *
 * Both sides cascade: deleting a tag must not leave orphan joins that would make an
 * item's tag list fail to resolve.
 */
@Entity(
    tableName = "item_tags",
    primaryKeys = ["item_uuid", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["item_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tag_id"), Index("item_uuid")],
)
internal data class ItemTagCrossRef(
    @ColumnInfo(name = "item_uuid")
    val itemUuid: String,

    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)
