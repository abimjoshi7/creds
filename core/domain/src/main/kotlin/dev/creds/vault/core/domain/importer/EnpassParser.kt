package dev.creds.vault.core.domain.importer

import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.FieldHistoryEntry
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/** One item ready to import, with the field history that came with it. */
data class ImportedItem(
    val item: VaultItem,
    /** Previous values by index into [VaultItem.fields], newest first. */
    val history: Map<Int, List<FieldHistoryEntry>> = emptyMap(),
    val tagNames: List<String> = emptyList(),
)

/** What an import could not carry over, so the preview can say so before anything is written. */
data class ImportWarnings(
    /** History entries Enpass stored encrypted, which cannot be read outside Enpass. */
    val encryptedHistorySkipped: Int = 0,
    val attachmentsSkipped: Int = 0,
    val deletedFieldsSkipped: Int = 0,
    /** Field types Creds does not know, imported as text with their sensitivity kept. */
    val unknownFieldTypes: Set<String> = emptySet(),
    /** Enpass templates with no Creds equivalent, imported as "Other". */
    val unmappedTemplates: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = encryptedHistorySkipped == 0 && attachmentsSkipped == 0 && deletedFieldsSkipped == 0 &&
            unknownFieldTypes.isEmpty() && unmappedTemplates.isEmpty()
}

data class ParsedImport(val items: List<ImportedItem>, val warnings: ImportWarnings)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Enpass's JSON export, read losslessly into Creds items.
 *
 * Creds' field types were modelled on Enpass's, so types map one to one by id, and every
 * field is kept — empty template slots and section headings included — in Enpass's order.
 * What cannot be kept is reported in [ImportWarnings] instead of dropped silently.
 *
 * Sensitivity is only ever raised: a field Enpass marked sensitive stays sensitive, and a
 * field whose type is secret in Creds (a TOTP seed, which Enpass does not mark) becomes
 * sensitive. An importer must never be how a secret reaches the search index.
 *
 * The subtitle is recomputed rather than taken from the file, because Creds only draws
 * subtitles from non-sensitive fields and an exported subtitle carries no such promise.
 *
 * Exception messages never quote the file: they may be shown, and the file is secrets.
 */
object EnpassParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private const val MILLIS = 1000L

    fun parse(text: String, newUuid: () -> String = { UUID.randomUUID().toString() }): ParsedImport {
        val export = try {
            json.decodeFromString(EnpassExport.serializer(), text)
        } catch (e: SerializationException) {
            throw ImportException("This is not an Enpass JSON export.", e)
        } catch (e: IllegalArgumentException) {
            throw ImportException("This is not an Enpass JSON export.", e)
        }

        var encryptedHistory = 0
        var attachments = 0
        var deletedFields = 0
        val unknownTypes = sortedSetOf<String>()
        val unmapped = sortedSetOf<String>()
        val folderNames = export.folders.associate { it.uuid to it.title.trim() }

        val items = export.items.map { source ->
            val (template, mapped) = templateFor(source.category, source.templateType)
            if (!mapped) unmapped += source.templateType.ifEmpty { source.category }
            attachments += source.attachments.size

            val kept = source.fields.sortedBy { it.order }.filter { field ->
                if (field.deleted.flag()) deletedFields++
                !field.deleted.flag()
            }

            val history = HashMap<Int, List<FieldHistoryEntry>>()
            val fields = kept.mapIndexed { index, field ->
                val known = FieldType.entries.firstOrNull { it.id == field.type }
                if (known == null) unknownTypes += field.type
                val type = known ?: FieldType.TEXT

                val readable = field.history.filterNot { it.encrypted.flag() }
                encryptedHistory += field.history.size - readable.size
                if (readable.isNotEmpty()) {
                    history[index] = readable
                        .sortedByDescending { it.updatedAt }
                        .map { FieldHistoryEntry(id = 0, value = it.value, replacedAt = it.updatedAt * MILLIS) }
                }

                VaultField(
                    uid = 0,
                    type = type,
                    label = field.label,
                    value = if (type == FieldType.SECTION) "" else field.value,
                    sensitive = field.sensitive.flag() || type.defaultSensitive,
                    order = index,
                    updatedAt = field.updatedAt * MILLIS,
                    valueUpdatedAt = field.valueUpdatedAt * MILLIS,
                )
            }

            ImportedItem(
                item = VaultItem(
                    uuid = source.uuid.takeIf(::isUuid) ?: newUuid(),
                    template = template,
                    title = source.title.trim().ifEmpty { TemplateCatalog.displayName(template) },
                    subtitle = TemplateCatalog.subtitleFor(template, fields),
                    note = source.note,
                    favorite = source.favorite.flag(),
                    archived = source.archived.flag(),
                    trashed = source.trashed.flag(),
                    createdAt = source.createdAt * MILLIS,
                    updatedAt = source.updatedAt * MILLIS,
                    fields = fields,
                ),
                history = history,
                tagNames = source.folders.mapNotNull { folderNames[it]?.takeIf(String::isNotEmpty) }.distinct(),
            )
        }

        return ParsedImport(
            items = items,
            warnings = ImportWarnings(encryptedHistory, attachments, deletedFields, unknownTypes, unmapped),
        )
    }

    /**
     * Enpass's `category` and `template_type` to a Creds template. The second value says
     * whether the mapping was meant, or a fallback to [Template.MISC].
     */
    internal fun templateFor(category: String, templateType: String): Pair<Template, Boolean> {
        val type = templateType.lowercase()
        val mapped = when {
            category == "login" || type.startsWith("login.") -> Template.LOGIN
            type == "finance.bankaccount" -> Template.BANK_ACCOUNT
            category == "creditcard" || type.startsWith("creditcard.") || type == "finance.creditcard" -> Template.CARD
            category == "note" || type.startsWith("note.") -> Template.NOTE
            type == "computer.wifi" || type == "computer.wireless" -> Template.WIFI
            type in setOf("computer.server", "computer.database", "computer.ftp", "computer.webhosting") -> Template.SERVER
            type == "travel.passport" -> Template.PASSPORT
            category == "identity" || type.startsWith("identity.") -> Template.IDENTITY
            // "Other" templates in any category are Enpass's own catch-all, not a loss.
            type.endsWith(".other") || category == "misc" -> Template.MISC
            else -> null
        }
        return (mapped ?: Template.MISC) to (mapped != null)
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess && value.length == 36

    /** Enpass writes flags as 0/1 in some versions and true/false in others. */
    private fun JsonElement?.flag(): Boolean {
        val primitive = this as? JsonPrimitive ?: return false
        return primitive.booleanOrNull ?: ((primitive.intOrNull ?: 0) != 0)
    }
}

@Serializable
private data class EnpassExport(
    val items: List<EnpassItem> = emptyList(),
    val folders: List<EnpassFolder> = emptyList(),
)

@Serializable
private data class EnpassFolder(
    val uuid: String = "",
    val title: String = "",
)

@Serializable
private data class EnpassItem(
    val uuid: String = "",
    val title: String = "",
    val note: String = "",
    val category: String = "",
    @SerialName("template_type") val templateType: String = "",
    val favorite: JsonElement? = null,
    val archived: JsonElement? = null,
    val trashed: JsonElement? = null,
    val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val fields: List<EnpassField> = emptyList(),
    val folders: List<String> = emptyList(),
    val attachments: List<JsonElement> = emptyList(),
)

@Serializable
private data class EnpassField(
    val type: String = "text",
    val label: String = "",
    val value: String = "",
    val sensitive: JsonElement? = null,
    val order: Int = 0,
    val deleted: JsonElement? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("value_updated_at") val valueUpdatedAt: Long = 0,
    val history: List<EnpassHistory> = emptyList(),
)

@Serializable
private data class EnpassHistory(
    val value: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
    val encrypted: JsonElement? = null,
)
