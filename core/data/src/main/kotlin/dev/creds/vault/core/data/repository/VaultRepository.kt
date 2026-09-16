package dev.creds.vault.core.data.repository

import androidx.room.withTransaction
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.dao.SearchQuery
import dev.creds.vault.core.data.db.entity.FieldEntity
import dev.creds.vault.core.data.db.entity.ItemEntity
import dev.creds.vault.core.data.db.entity.ItemTagCrossRef
import dev.creds.vault.core.data.db.entity.TagEntity
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem

/**
 * The module's write path.
 *
 * Every mutation that touches searchable content goes through here so the FTS index is
 * rewritten in the same transaction as the rows it describes. The DAOs are `internal`
 * precisely so nothing can bypass that and leave search describing a vault that no
 * longer exists.
 *
 * Takes the [VaultKey] per call rather than holding one. The key's lifetime belongs to
 * `VaultSession`, and a repository caching it would keep key material alive past a lock.
 */
class VaultRepository internal constructor(
    private val database: CredsDatabase,
    private val fieldCipher: FieldCipher,
) {

    private val itemDao = database.itemDao()
    private val fieldDao = database.fieldDao()
    private val tagDao = database.tagDao()
    private val searchDao = database.searchDao()

    /**
     * Inserts or replaces an item and all of its fields.
     *
     * Fields are replaced wholesale rather than diffed: an edit screen hands back the
     * finished list, and reconciling per-field identity would be a lot of machinery to
     * save a few row writes on an object that has a handful of them.
     */
    suspend fun save(vaultKey: VaultKey, item: VaultItem, tags: List<Tag> = item.tags) {
        database.withTransaction {
            itemDao.upsert(item.toEntity(vaultKey))

            fieldDao.deleteForItem(item.uuid)
            val fields = item.fields
                .filter { it.type.isValueBearing || it.value.isEmpty() }
                .map { it.toEntity(vaultKey, item.uuid) }
            if (fields.isNotEmpty()) fieldDao.insertAll(fields)

            tagDao.unlinkAll(item.uuid)
            if (tags.isNotEmpty()) {
                tagDao.link(tags.map { ItemTagCrossRef(item.uuid, it.id) })
            }

            reindex(item)
        }
    }

    /** Reads an item back in plaintext. Null when it does not exist. */
    suspend fun load(vaultKey: VaultKey, uuid: String): VaultItem? {
        val entity = itemDao.byUuid(uuid) ?: return null
        val fields = fieldDao.forItem(uuid)
        val tags = tagDao.forItem(uuid)
        return entity.toModel(vaultKey, fields, tags)
    }

    /**
     * Full-text search.
     *
     * Returns item uuids; the caller decides how many to hydrate. Blank or
     * punctuation-only input yields no filter rather than no results, because an empty
     * search box means "everything", not "nothing".
     */
    suspend fun search(raw: String): List<String>? {
        val query = SearchQuery.sanitize(raw) ?: return null
        return searchDao.search(query)
    }

    suspend fun trash(uuid: String, now: Long) {
        database.withTransaction {
            itemDao.trash(uuid, now)
            // A trashed item must stop appearing in search immediately.
            searchDao.deleteFor(uuid)
        }
    }

    suspend fun restore(vaultKey: VaultKey, uuid: String, now: Long) {
        database.withTransaction {
            itemDao.restore(uuid, now)
            load(vaultKey, uuid)?.let { reindex(it) }
        }
    }

    /** Hard delete. Cascades to fields, history, tags, associations and scores. */
    suspend fun purge(uuid: String) {
        database.withTransaction {
            searchDao.deleteFor(uuid)
            itemDao.purge(uuid)
        }
    }

    suspend fun upsertTag(tag: Tag): Long =
        tagDao.upsert(TagEntity(id = tag.id, name = tag.name, color = tag.color))

    /**
     * Rewrites this item's row in the FTS index.
     *
     * Delete-then-insert rather than update: FTS5 has no stable rowid we track, and one
     * item maps to exactly one row here.
     */
    private suspend fun reindex(item: VaultItem) {
        searchDao.deleteFor(item.uuid)
        if (item.trashed) return

        val content = buildSearchContent(item)
        if (content.isNotBlank()) searchDao.index(item.uuid, content)
    }

    /**
     * Everything about an item that is safe to index.
     *
     * Sensitive field values are excluded here and their `search_text` column is null —
     * two independent expressions of the same rule, because a secret reaching the FTS
     * index would be invisible until someone searched for a password and found it.
     */
    private fun buildSearchContent(item: VaultItem): String = buildString {
        append(item.title)
        if (item.subtitle.isNotEmpty()) append(' ').append(item.subtitle)
        if (item.note.isNotEmpty()) append(' ').append(item.note)

        item.fields
            .filter { !it.deleted && !it.sensitive && it.type.isValueBearing }
            .forEach { field ->
                if (field.label.isNotEmpty()) append(' ').append(field.label)
                if (field.value.isNotEmpty()) append(' ').append(field.value)
            }
    }

    private fun VaultItem.toEntity(vaultKey: VaultKey) = ItemEntity(
        uuid = uuid,
        template = template,
        title = title,
        subtitle = subtitle,
        noteEnc = fieldCipher.sealNote(vaultKey, uuid, note),
        icon = icon,
        favorite = favorite,
        archived = archived,
        trashed = trashed,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun VaultField.toEntity(vaultKey: VaultKey, itemUuid: String) = FieldEntity(
        uid = if (uid == 0L) 0 else uid,
        itemUuid = itemUuid,
        type = type,
        label = label,
        valueEnc = if (value.isEmpty()) {
            null
        } else {
            fieldCipher.sealValue(vaultKey, itemUuid, type, value)
        },
        sensitive = sensitive,
        ord = order,
        deleted = deleted,
        updatedAt = updatedAt,
        valueUpdatedAt = valueUpdatedAt,
        searchText = fieldCipher.searchText(sensitive, value),
        reuseHmac = fieldCipher.reuseHmac(vaultKey, type, value),
        sha1Prefix = fieldCipher.sha1Prefix(type, value),
    )

    private fun ItemEntity.toModel(
        vaultKey: VaultKey,
        fields: List<FieldEntity>,
        tags: List<TagEntity>,
    ) = VaultItem(
        uuid = uuid,
        template = template,
        title = title,
        subtitle = subtitle,
        note = fieldCipher.openNote(vaultKey, uuid, noteEnc),
        icon = icon,
        favorite = favorite,
        archived = archived,
        trashed = trashed,
        createdAt = createdAt,
        updatedAt = updatedAt,
        fields = fields.map { it.toModel(vaultKey) },
        tags = tags.map { Tag(id = it.id, name = it.name, color = it.color) },
    )

    private fun FieldEntity.toModel(vaultKey: VaultKey) = VaultField(
        uid = uid,
        type = type,
        label = label,
        value = valueEnc?.let { fieldCipher.openValue(vaultKey, itemUuid, type, it) } ?: "",
        sensitive = sensitive,
        order = ord,
        deleted = deleted,
        updatedAt = updatedAt,
        valueUpdatedAt = valueUpdatedAt,
    )
}
