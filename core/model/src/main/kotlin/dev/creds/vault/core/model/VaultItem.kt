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
) {
    fun firstOfType(type: FieldType): VaultField? =
        fields.firstOrNull { !it.deleted && it.type == type }

    val primaryUsername: VaultField?
        get() = firstOfType(FieldType.USERNAME) ?: firstOfType(FieldType.EMAIL)

    val primaryPassword: VaultField? get() = firstOfType(FieldType.PASSWORD)
}

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
