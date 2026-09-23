package dev.creds.vault.core.model

/**
 * A decrypted item as the UI sees it.
 *
 * This is the plaintext form. It exists only while the vault is unlocked and is built
 * on demand from the encrypted rows; it is never cached across a lock.
 */
data class VaultItem(
    val uuid: String,
    val template: Template,
    val title: String,
    val subtitle: String = "",
    val note: String = "",
    val icon: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val fields: List<VaultField> = emptyList(),
    val tags: List<Tag> = emptyList(),
    /**
     * Metadata of the item's files. Ignored when saving: files are added and removed
     * through explicit attachment changes, because their bytes do not live in this object.
     */
    val attachments: List<Attachment> = emptyList(),
) {
    fun firstOfType(type: FieldType): VaultField? =
        fields.firstOrNull { !it.deleted && it.type == type }

    val primaryUsername: VaultField?
        get() = firstOfType(FieldType.USERNAME) ?: firstOfType(FieldType.EMAIL)

    val primaryPassword: VaultField? get() = firstOfType(FieldType.PASSWORD)
}

/**
 * An item as a list row sees it.
 *
 * Built entirely from columns that are plaintext inside the encrypted database, so
 * rendering the vault list decrypts nothing. Anything that needs a field value loads the
 * full [VaultItem].
 */
data class VaultItemSummary(
    val uuid: String,
    val template: Template,
    val title: String,
    val subtitle: String = "",
    val icon: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val updatedAt: Long = 0L,
    val tags: List<Tag> = emptyList(),
)

/**
 * One field of an item.
 *
 * [value] is plaintext and therefore short-lived. Callers that hold a sensitive value
 * for any length of time should take a copy they can zero rather than retaining this.
 */
data class VaultField(
    val uid: Long,
    val type: FieldType,
    val label: String,
    val value: String,
    val sensitive: Boolean = type.defaultSensitive,
    val order: Int = 0,
    val deleted: Boolean = false,
    val updatedAt: Long = 0L,
    /** When the *value* last changed, which is what the staleness audit measures. */
    val valueUpdatedAt: Long = updatedAt,
)

/** A user-created label. Free-form, many-to-many, orthogonal to [Template]. */
data class Tag(
    val id: Long,
    val name: String,
    val color: Int? = null,
)

/** A previous value of a field, decrypted for display. Newest first from the repository. */
data class FieldHistoryEntry(
    val id: Long,
    val value: String,
    val replacedAt: Long,
)

/** A value the generator produced and the user took, decrypted for display. Newest first. */
data class GeneratedValue(
    val id: Long,
    val value: String,
    val createdAt: Long,
)

/** What an autofill association identifies. [id] is the stored `kind` column value. */
enum class AssociationKind(val id: String) {
    /** A native app: package name plus signing certificate. */
    APP("package"),

    /** A website, by registrable domain. */
    DOMAIN("domain"),
    ;

    companion object {
        fun fromId(id: String): AssociationKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Somewhere an item has been confirmed to fill.
 *
 * @param value a package name, or a registrable domain.
 * @param certSha256 for an app, the SHA-256 of its signing certificate(s), sorted and
 *   comma-joined; null for a domain.
 */
data class ItemAssociation(
    val kind: AssociationKind,
    val value: String,
    val certSha256: String?,
    val confirmedAt: Long,
)
