package dev.creds.vault.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A value the generator produced and the user took — copied, or put into an item.
 *
 * Exists for one failure: "I generated it, changed it on the site, then lost it before
 * saving". So it is short-lived by design — the newest few, for a day — and sealed under
 * a key of its own, since it belongs to no item.
 */
@Entity(
    tableName = "generator_history",
    indices = [Index("created_at")],
)
internal data class GeneratedValueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** AES-GCM under `VaultKey.generatorHistoryKey`, AAD `generator`. */
    @ColumnInfo(name = "value_enc", typeAffinity = ColumnInfo.BLOB)
    val valueEnc: ByteArray,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedValueEntity) return false
        return id == other.id && valueEnc.contentEquals(other.valueEnc) && createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + valueEnc.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
