package dev.creds.vault.core.data.db.dao

import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.VaultFilter

/** A parameterised statement: SQL with `?` placeholders and the values that fill them. */
internal data class SqlStatement(val sql: String, val args: List<Any>)

/**
 * Turns a [VaultFilter] into the item-list query.
 *
 * Built by hand rather than as a fixed `@Query` per combination: smart list, template,
 * any number of tags and a search term compose freely, and a DAO method per combination
 * would be dozens of near-identical statements that drift apart.
 *
 * Every user-supplied value is bound as an argument. Nothing typed by a person is ever
 * concatenated into the SQL text, including the FTS expression, which is sanitised by
 * [SearchQuery] *and* bound.
 *
 * Pure string building with no Android types, so it is tested on the host.
 */
internal object VaultQuery {

    /** Every smart list with data behind it. Recently used waits for usage tracking. */
    val SUPPORTED: Set<SmartList> = setOf(
        SmartList.ALL, SmartList.FAVORITES, SmartList.ARCHIVE, SmartList.TRASH,
        SmartList.WEAK, SmartList.REUSED, SmartList.BREACHED,
    )

    fun build(filter: VaultFilter): SqlStatement {
        require(filter.smartList in SUPPORTED) {
            "${filter.smartList} needs usage or audit data this build does not record"
        }

        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        where += when (filter.smartList) {
            SmartList.FAVORITES -> "trashed = 0 AND archived = 0 AND favorite = 1"
            SmartList.ARCHIVE -> "trashed = 0 AND archived = 1"
            SmartList.TRASH -> "trashed = 1"
            else -> "trashed = 0 AND archived = 0"
        }

        when (filter.smartList) {
            SmartList.WEAK -> AuditSql.weakItems
            SmartList.REUSED -> AuditSql.reusedItems
            SmartList.BREACHED -> AuditSql.breachedItems
            else -> null
        }?.let { audit ->
            where += "uuid IN (${audit.sql})"
            args.addAll(audit.args)
        }

        filter.template?.let {
            where += "template = ?"
            args += it.id
        }

        if (filter.tagIds.isNotEmpty()) {
            // AND across tags: an item qualifies only when it carries every selected one.
            // Counting DISTINCT guards against a duplicate join row satisfying two slots.
            val placeholders = filter.tagIds.joinToString(",") { "?" }
            where += """
                uuid IN (
                    SELECT item_uuid FROM item_tags
                    WHERE tag_id IN ($placeholders)
                    GROUP BY item_uuid
                    HAVING COUNT(DISTINCT tag_id) = ?
                )
            """.trimIndent()
            args.addAll(filter.tagIds.sorted())
            args += filter.tagIds.size
        }

        if (filter.smartList == SmartList.TRASH) {
            // Trashed items are removed from the FTS index on purpose, so the trash is
            // searched by title and subtitle instead. It is small and rarely searched.
            SearchQuery.tokens(filter.query).forEach { token ->
                where += "(title LIKE ? ESCAPE '\\' OR subtitle LIKE ? ESCAPE '\\')"
                val pattern = "%${escapeLike(token)}%"
                args += pattern
                args += pattern
            }
        } else {
            SearchQuery.sanitize(filter.query)?.let { match ->
                where += "uuid IN (SELECT item_uuid FROM items_fts WHERE items_fts MATCH ?)"
                args += match
            }
        }

        // Most recently trashed first, because that is what someone is about to restore.
        val order = if (filter.smartList == SmartList.TRASH) {
            "updated_at DESC, uuid ASC"
        } else {
            "title COLLATE NOCASE ASC, uuid ASC"
        }

        val sql = "SELECT * FROM items WHERE ${where.joinToString(" AND ") { "($it)" }} ORDER BY $order"
        return SqlStatement(sql, args)
    }

    /** `%`, `_` and the escape character itself are literals in what a person typed. */
    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
