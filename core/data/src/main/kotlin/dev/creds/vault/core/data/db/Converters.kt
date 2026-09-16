package dev.creds.vault.core.data.db

import androidx.room.TypeConverter
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template

/**
 * Enum columns are stored as their stable string ids, never as ordinals.
 *
 * An ordinal is a position in a source file. Reordering `FieldType` — or inserting a new
 * constant in the middle of it — would silently reinterpret every stored row, turning a
 * password into a phone number. The ids exist precisely so that cannot happen, and both
 * enums already decode unknown ids to a safe fallback rather than throwing, so a vault
 * written by a newer build still opens.
 */
internal object Converters {

    @TypeConverter
    fun templateToId(template: Template): String = template.id

    @TypeConverter
    fun templateFromId(id: String): Template = Template.fromId(id)

    @TypeConverter
    fun fieldTypeToId(type: FieldType): String = type.id

    @TypeConverter
    fun fieldTypeFromId(id: String): FieldType = FieldType.fromId(id)
}
