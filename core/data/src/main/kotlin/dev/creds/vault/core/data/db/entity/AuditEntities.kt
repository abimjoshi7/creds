package dev.creds.vault.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Where an item is allowed to autofill.
 *
 * [certSha256] is the trust model: a native app is matched on package name *and* the
 * SHA-256 of its signing certificate, recorded the first time the user confirms a fill.
 * A repackaged app shares the package name but never the certificate hash, so it gets
 * nothing. Null only for web domains, where the certificate is not the identity.
 */
@Entity(
    tableName = "item_associations",
    primaryKeys = ["item_uuid", "kind", "value"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["item_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("item_uuid"), Index(value = ["kind", "value"])],
)
internal data class ItemAssociationEntity(
    @ColumnInfo(name = "item_uuid")
    val itemUuid: String,

    /** `package` or `domain`. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /** Package name, or the eTLD+1 the Public Suffix List resolved. */
    @ColumnInfo(name = "value")
    val value: String,

    /** Hex SHA-256 of the signing certificate. Null for web domains. */
    @ColumnInfo(name = "cert_sha256")
    val certSha256: String? = null,

    /** Trust-on-first-use timestamp. */
    @ColumnInfo(name = "confirmed_at")
    val confirmedAt: Long,
)

/**
 * Cached audit results for one field: strength and breach status.
 *
 * Separate from `fields` because scoring is expensive and its inputs change on a
 * different cadence than the field itself — a new zxcvbn version rescores the whole
 * vault without touching a single value.
 *
 * A row is current while [checkedAt] is not older than the field's `value_updated_at`;
 * a stale row is ignored by every query rather than trusted.
 */
@Entity(
    tableName = "audit_scores",
    foreignKeys = [
        ForeignKey(
            entity = FieldEntity::class,
            parentColumns = ["uid"],
            childColumns = ["field_uid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("score")],
)
internal data class AuditScoreEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "field_uid")
    val fieldUid: Long,

    /** zxcvbn 0..4. */
    @ColumnInfo(name = "score")
    val score: Int,

    @ColumnInfo(name = "guesses_log10")
    val guessesLog10: Double,

    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,

    /** In the offline list, or seen by Have I Been Pwned. Added in schema 3. */
    @ColumnInfo(name = "breached", defaultValue = "0")
    val breached: Boolean = false,

    /** Have I Been Pwned's count; null until checked online. Added in schema 3. */
    @ColumnInfo(name = "breach_count")
    val breachCount: Int? = null,

    /** When the online check last ran for this value; null if never. Added in schema 3. */
    @ColumnInfo(name = "online_checked_at")
    val onlineCheckedAt: Long? = null,
)
