package dev.creds.vault.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

/**
 * The FTS5 index.
 *
 * Room has no `@Fts5` annotation — only `@Fts3` and `@Fts4` — so this table cannot be a
 * Room entity. It is created as a raw virtual table in the database callback, which
 * means Room's compile-time query verifier does not know it exists; hence
 * [SkipQueryVerification] on every method here. That annotation is load-bearing, not
 * decoration: without it these queries fail the build.
 *
 * SQLCipher 4.18.0 bundles SQLite with `ENABLE_FTS5` compiled in, which is what makes
 * this possible at all.
 *
 * The index is maintained by the repository inside the same transaction as the item
 * write rather than by SQL triggers. A trigger per field row would rebuild the whole
 * per-item aggregate once per field on every save; the repository already knows the
 * finished item and can write the row exactly once.
 */
@Dao
internal interface SearchDao {

    @SkipQueryVerification
    @Query("INSERT INTO items_fts(item_uuid, content) VALUES (:itemUuid, :content)")
    suspend fun index(itemUuid: String, content: String)

    @SkipQueryVerification
    @Query("DELETE FROM items_fts WHERE item_uuid = :itemUuid")
    suspend fun deleteFor(itemUuid: String)

    /**
     * Full-text match.
     *
     * The caller passes an FTS5 query expression. Callers must escape user input — a
     * bare apostrophe or a stray `"` is a syntax error in FTS5, not a no-match — which
     * `SearchQuery.sanitize` handles.
     */
    @SkipQueryVerification
    @Query("SELECT item_uuid FROM items_fts WHERE items_fts MATCH :query")
    suspend fun search(query: String): List<String>

    @SkipQueryVerification
    @Query("DELETE FROM items_fts")
    suspend fun clear()

    @SkipQueryVerification
    @Query("SELECT COUNT(*) FROM items_fts")
    suspend fun count(): Int
}

/** Turns what a person typed into something FTS5 will parse. */
internal object SearchQuery {

    /**
     * Wraps each whitespace-separated token as a quoted prefix term.
     *
     * Quoting is what makes `o'brien` or `bank.com` safe: inside double quotes FTS5
     * treats the token as a literal string, so punctuation cannot be read as operators.
     * Embedded double quotes are doubled, which is FTS5's own escape.
     *
     * Returns null when nothing usable remains, which callers treat as "no filter"
     * rather than as "match nothing".
     */
    fun sanitize(raw: String): String? {
        val terms = tokens(raw).map { it.replace("\"", "\"\"") }
        if (terms.isEmpty()) return null

        return terms.joinToString(" ") { "\"$it\"*" }
    }

    /**
     * What a person typed, split into terms worth searching for.
     *
     * A token with no letter or digit is dropped. The FTS tokenizer discards punctuation
     * anyway, so `,` would become an empty phrase that matches nothing — a stray comma
     * would empty the list instead of being ignored.
     */
    fun tokens(raw: String): List<String> =
        raw.trim()
            .split(Regex("\\s+"))
            .filter { token -> token.any(Char::isLetterOrDigit) }
}
