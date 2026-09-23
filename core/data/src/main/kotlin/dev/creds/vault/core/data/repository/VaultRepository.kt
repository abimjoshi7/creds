package dev.creds.vault.core.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.db.CredsDatabase
import dev.creds.vault.core.data.db.dao.SearchQuery
import dev.creds.vault.core.data.db.dao.VaultQuery
import dev.creds.vault.core.data.db.entity.FieldEntity
import dev.creds.vault.core.data.db.entity.FieldHistoryEntity
import dev.creds.vault.core.data.db.entity.ItemEntity
import dev.creds.vault.core.data.db.entity.ItemTagCrossRef
import dev.creds.vault.core.data.db.entity.TagEntity
import dev.creds.vault.core.domain.tag.TagNameCheck
import dev.creds.vault.core.domain.tag.TagNames
import dev.creds.vault.core.model.FieldHistoryEntry
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.model.VaultCounts
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultFilter
import dev.creds.vault.core.model.VaultItem
import dev.creds.vault.core.model.VaultItemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    private val fieldHistoryDao = database.fieldHistoryDao()
    private val tagDao = database.tagDao()
    private val searchDao = database.searchDao()

    /**
     * Inserts or updates an item and its fields.
     *
     * Existing field uids are preserved so field history stays attached. When a
     * sensitive value changes, the previous ciphertext is recorded before the row is
     * overwritten — that is the whole point of history ("the new password does not work").
     */
    suspend fun save(vaultKey: VaultKey, item: VaultItem, tags: List<Tag> = item.tags) {
        database.withTransaction {
            itemDao.upsert(item.toEntity(vaultKey))

            val existing = fieldDao.forItemAll(item.uuid).associateBy { it.uid }
            val incoming = item.fields.filter { it.type.isValueBearing || it.value.isEmpty() }
            val keptUids = mutableSetOf<Long>()

            for (field in incoming) {
                if (field.uid != 0L) {
                    keptUids += field.uid
                    val previous = existing[field.uid]
                    if (previous != null &&
                        !previous.deleted &&
                        previous.sensitive &&
                        previous.valueEnc != null
                    ) {
                        val previousValue = fieldCipher.openValue(
                            vaultKey,
                            item.uuid,
                            previous.type,
                            previous.valueEnc,
                        )
                        if (previousValue.isNotEmpty() && previousValue != field.value) {
                            fieldHistoryDao.insert(
                                FieldHistoryEntity(
                                    fieldUid = field.uid,
                                    valueEnc = previous.valueEnc,
                                    replacedAt = item.updatedAt,
                                ),
                            )
                        }
                    }
                }

                val uid = fieldDao.upsert(field.toEntity(vaultKey, item.uuid))
                if (field.uid == 0L) keptUids += uid else keptUids += field.uid
            }

            existing.values
                .filter { !it.deleted && it.uid !in keptUids }
                .forEach { fieldDao.markDeleted(it.uid, item.updatedAt) }

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
     * Previous values of a field, newest first.
     *
     * Empty when the field is new or has never changed. Decryption uses the field's
     * current type — history is only written for value changes, not type changes.
     */
    suspend fun fieldHistory(vaultKey: VaultKey, fieldUid: Long): List<FieldHistoryEntry> {
        if (fieldUid == 0L) return emptyList()
        val field = fieldDao.byUid(fieldUid) ?: return emptyList()
        return fieldHistoryDao.forField(fieldUid).map { entry ->
            FieldHistoryEntry(
                id = entry.id,
                value = fieldCipher.openValue(vaultKey, field.itemUuid, field.type, entry.valueEnc),
                replacedAt = entry.replacedAt,
            )
        }
    }

    suspend fun fieldHistoryCount(fieldUid: Long): Int =
        if (fieldUid == 0L) 0 else fieldHistoryDao.countForField(fieldUid)

    /**
     * Signals when an open item needs re-reading.
     *
     * Emits true each time the item, its fields, its tags, or the name or colour of any
     * tag may have changed, and false once the item no longer exists. It carries no
     * content on purpose: the caller re-reads with [load] under its own unlocked check,
     * so no vault key has to live inside a long-running flow.
     */
    fun observeItemChanges(uuid: String): Flow<Boolean> =
        combine(itemDao.observeUpdatedAt(uuid), tagDao.observeAll()) { updatedAt, tags ->
            updatedAt to tags
        }
            // Room re-runs both queries on any write to their tables, including writes to
            // other items. Only a change that could alter this item gets through.
            .distinctUntilChanged()
            .map { (updatedAt, _) -> updatedAt != null }

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

    /**
     * The vault list for [filter], kept current as the vault changes.
     *
     * Decrypts nothing: every column a row needs is plaintext inside the encrypted
     * database. Tags are resolved in memory from the join table so a rename shows up in
     * every row without re-running the item query.
     */
    fun observeItems(filter: VaultFilter): Flow<List<VaultItemSummary>> {
        val statement = VaultQuery.build(filter)
        val items = itemDao.observe(SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()))

        return combine(items, tagDao.observeAll(), tagDao.observeCrossRefs()) { rows, tags, refs ->
            val tagsById = tags.associate { it.id to it.toModel() }
            // Grouped in tag-list order, so each row's tags come out alphabetical.
            val tagOrder = tags.withIndex().associate { (index, tag) -> tag.id to index }
            val tagsByItem = refs.groupBy({ it.itemUuid }, { it.tagId })

            rows.map { row ->
                val itemTags = tagsByItem[row.uuid].orEmpty()
                    .sortedBy { tagOrder[it] ?: Int.MAX_VALUE }
                    .mapNotNull(tagsById::get)
                row.toSummary(itemTags)
            }
        }
    }

    fun observeTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { tags -> tags.map { it.toModel() } }

    fun observeCounts(): Flow<VaultCounts> = combine(
        itemDao.observeListCounts(),
        itemDao.observeTemplateCounts(),
        tagDao.observeTagCounts(),
    ) { lists, templates, tags ->
        VaultCounts(
            all = lists.active,
            favorites = lists.favorites,
            archive = lists.archived,
            trash = lists.trashed,
            byTemplate = templates.associate { it.template to it.count },
            byTag = tags.associate { it.tagId to it.count },
        )
    }

    suspend fun setFavorite(uuid: String, favorite: Boolean, now: Long) =
        itemDao.setFavorite(uuid, favorite, now)

    /** Archived items stay searchable from the archive; only the default views hide them. */
    suspend fun setArchived(uuid: String, archived: Boolean, now: Long) =
        itemDao.setArchived(uuid, archived, now)

    /** Hard-deletes everything in the trash, in one transaction. Returns how many. */
    suspend fun emptyTrash(): Int = database.withTransaction {
        val uuids = itemDao.trashedUuids()
        uuids.forEach { uuid ->
            searchDao.deleteFor(uuid)
            itemDao.purge(uuid)
        }
        uuids.size
    }

    /**
     * Creates a tag, or explains why not.
     *
     * Names are normalised and compared case-insensitively against every existing tag, so
     * `#Work` cannot be created next to `work`. The comparison runs in Kotlin rather than
     * SQL because SQLite's `NOCASE` only folds ASCII.
     */
    suspend fun createTag(rawName: String, color: Int? = null): TagResult =
        database.withTransaction {
            when (val check = TagNames.check(rawName)) {
                TagNameCheck.Blank -> TagResult.Blank
                is TagNameCheck.TooLong -> TagResult.TooLong(check.max)
                is TagNameCheck.Valid -> {
                    duplicateOf(check.name, excludingId = null)?.let { return@withTransaction it }
                    val entity = TagEntity(name = check.name, color = color)
                    val id = tagDao.insert(entity)
                    TagResult.Saved(entity.copy(id = id).toModel())
                }
            }
        }

    /** Renames or recolours a tag. Items filed under it are untouched, by design. */
    suspend fun updateTag(tag: Tag): TagResult = database.withTransaction {
        when (val check = TagNames.check(tag.name)) {
            TagNameCheck.Blank -> TagResult.Blank
            is TagNameCheck.TooLong -> TagResult.TooLong(check.max)
            is TagNameCheck.Valid -> {
                if (tagDao.byId(tag.id) == null) return@withTransaction TagResult.NotFound
                duplicateOf(check.name, excludingId = tag.id)?.let { return@withTransaction it }
                val entity = TagEntity(id = tag.id, name = check.name, color = tag.color)
                tagDao.update(entity)
                TagResult.Saved(entity.toModel())
            }
        }
    }

    /**
     * Deletes a tag. The items it was on are kept and simply lose the tag.
     *
     * Those items are marked updated first, while the join rows still say which ones
     * they are, so a future sync layer sees the change on each of them.
     */
    suspend fun deleteTag(id: Long, now: Long) {
        database.withTransaction {
            itemDao.touchTagged(id, now)
            tagDao.delete(id)
        }
    }

    /**
     * Replaces an item's tags.
     *
     * Tags are not part of the FTS content, so this needs no reindex and no vault key.
     */
    suspend fun setItemTags(uuid: String, tagIds: Set<Long>, now: Long) {
        database.withTransaction {
            tagDao.unlinkAll(uuid)
            if (tagIds.isNotEmpty()) tagDao.link(tagIds.map { ItemTagCrossRef(uuid, it) })
            itemDao.touch(uuid, now)
        }
    }

    private suspend fun duplicateOf(name: String, excludingId: Long?): TagResult.Duplicate? =
        tagDao.all()
            .firstOrNull { it.id != excludingId && TagNames.sameName(it.name, name) }
            ?.let { TagResult.Duplicate(it.name) }

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

    private fun TagEntity.toModel() = Tag(id = id, name = name, color = color)

    private fun ItemEntity.toSummary(tags: List<Tag>) = VaultItemSummary(
        uuid = uuid,
        template = template,
        title = title,
        subtitle = subtitle,
        icon = icon,
        favorite = favorite,
        archived = archived,
        trashed = trashed,
        updatedAt = updatedAt,
        tags = tags,
    )

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
        tags = tags.map { it.toModel() },
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

/** The outcome of creating or editing a tag. */
sealed interface TagResult {

    data class Saved(val tag: Tag) : TagResult

    data object Blank : TagResult

    data class TooLong(val max: Int) : TagResult

    /** Another tag already has this name, ignoring case; [existing] is its spelling. */
    data class Duplicate(val existing: String) : TagResult

    /** The tag was deleted while being edited. */
    data object NotFound : TagResult
}
