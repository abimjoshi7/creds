package dev.creds.vault.core.domain.importer

import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.VaultItem

/** An item already in the vault, as deduplication sees it. */
data class ExistingItemKey(val uuid: String, val title: String, val username: String)

enum class ImportStatus {
    NEW,

    /** Same title and username as something already in the vault, or earlier in the file. */
    DUPLICATE,
}

data class ImportRow(
    val imported: ImportedItem,
    val status: ImportStatus,
    /** Whether it will be written. New items start selected, duplicates do not. */
    val selected: Boolean = status == ImportStatus.NEW,
)

/**
 * The preview between parsing and writing: which items are new, which are duplicates.
 *
 * Duplicates are matched on title and username, trimmed and ignoring case — the same
 * login exported twice, or imported twice. They are shown, not hidden, and can still be
 * chosen. An imported item whose uuid is already taken gets a fresh one, so importing can
 * never overwrite an existing item.
 */
object ImportPlanner {

    fun plan(parsed: ParsedImport, existing: List<ExistingItemKey>, newUuid: () -> String): List<ImportRow> {
        val seen = existing.mapTo(HashSet()) { key(it.title, it.username) }
        val takenUuids = existing.mapTo(HashSet()) { it.uuid }

        return parsed.items.map { imported ->
            val item = imported.item
            val k = key(item.title, usernameOf(item))
            val status = if (k in seen) ImportStatus.DUPLICATE else ImportStatus.NEW
            seen += k

            val uuid = if (item.uuid in takenUuids) newUuid() else item.uuid
            takenUuids += uuid
            ImportRow(imported.copy(item = item.copy(uuid = uuid)), status)
        }
    }

    /** The first non-blank username, else the first non-blank email. Template slots are often empty. */
    fun usernameOf(item: VaultItem): String {
        val live = item.fields.filter { !it.deleted && it.value.isNotBlank() }
        return (live.firstOrNull { it.type == FieldType.USERNAME } ?: live.firstOrNull { it.type == FieldType.EMAIL })?.value.orEmpty()
    }

    private fun key(title: String, username: String) = title.trim().lowercase() to username.trim().lowercase()
}
