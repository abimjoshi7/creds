package dev.creds.vault.items

import dev.creds.vault.core.domain.template.FieldSpec
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.VaultField

/**
 * One field as the editor holds it.
 *
 * [key] identifies the row across recompositions and edits. Saved fields are keyed by
 * their database uid; fields that do not exist yet get a key that is unique within the
 * draft, so two added fields with the same label never collide.
 */
data class EditableField(
    val key: String,
    val uid: Long,
    val type: FieldType,
    val label: String,
    val value: String,
    val sensitive: Boolean,
    val valueUpdatedAt: Long,
) {
    val isSection: Boolean get() = type == FieldType.SECTION

    companion object {
        fun from(field: VaultField, key: String = keyFor(field)) = EditableField(
            key = key,
            uid = field.uid,
            type = field.type,
            label = field.label,
            value = field.value,
            sensitive = field.sensitive,
            valueUpdatedAt = field.valueUpdatedAt,
        )

        fun keyFor(field: VaultField): String =
            if (field.uid != 0L) "uid:${field.uid}" else "new:${field.order}"
    }
}

/**
 * What the user can change in the editor, compared as a whole to decide whether leaving
 * would lose anything.
 */
data class EditorSnapshot(
    val title: String,
    val note: String,
    val favorite: Boolean,
    val fields: List<EditableField>,
)

/**
 * Turns the editor's rows back into fields to save, in on-screen order.
 *
 * `valueUpdatedAt` moves only when the value itself changed. It is what the staleness
 * audit reads, so relabelling, reordering or re-saving an untouched password must not
 * reset it.
 */
fun List<EditableField>.toVaultFields(
    baseline: List<EditableField>,
    now: Long,
): List<VaultField> {
    val originalValues = baseline.associate { it.key to it.value }
    return mapIndexed { index, field ->
        val valueChanged = field.uid == 0L || originalValues[field.key] != field.value
        VaultField(
            uid = field.uid,
            type = field.type,
            label = field.label.trim().ifEmpty { CustomFieldKind.forType(field.type).displayName },
            value = if (field.isSection) "" else field.value,
            sensitive = field.sensitive,
            order = index,
            updatedAt = now,
            valueUpdatedAt = if (valueChanged) now else field.valueUpdatedAt,
        )
    }
}

/**
 * The kinds of field a user can add to any item, whatever its template.
 *
 * Built on [FieldSpec], so the rule that a field may be made more secret than its type
 * but never less is enforced here as well: "Hidden text" is a sensitive `TEXT`, and there
 * is no way to ask for a non-sensitive password.
 */
enum class CustomFieldKind(
    val displayName: String,
    val type: FieldType,
    sensitive: Boolean = type.defaultSensitive,
) {
    TEXT("Text", FieldType.TEXT),
    HIDDEN("Hidden text", FieldType.TEXT, sensitive = true),
    PASSWORD("Password", FieldType.PASSWORD),
    PIN("PIN", FieldType.PIN),
    USERNAME("Username", FieldType.USERNAME),
    EMAIL("Email", FieldType.EMAIL),
    URL("Website", FieldType.URL),
    PHONE("Phone", FieldType.PHONE),
    TOTP("One-time code", FieldType.TOTP),
    NUMBER("Number", FieldType.NUMERIC),
    DATE("Date", FieldType.DATE),
    MULTILINE("Multi-line text", FieldType.MULTILINE),
    SECTION("Section heading", FieldType.SECTION),
    ;

    private val spec = FieldSpec(type, displayName, sensitive)

    val sensitive: Boolean get() = spec.sensitive

    fun newField(key: String, label: String, now: Long) = EditableField(
        key = key,
        uid = 0L,
        type = type,
        label = label.trim().ifEmpty { displayName },
        value = "",
        sensitive = sensitive,
        valueUpdatedAt = now,
    )

    companion object {
        /** A fallback label for a field saved without one. */
        fun forType(type: FieldType): CustomFieldKind =
            entries.firstOrNull { it.type == type && it.sensitive == type.defaultSensitive } ?: TEXT
    }
}
