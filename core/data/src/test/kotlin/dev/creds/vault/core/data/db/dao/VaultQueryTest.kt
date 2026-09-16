package dev.creds.vault.core.data.db.dao

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultFilter
import org.junit.jupiter.api.Test

/**
 * The item-list query builder.
 *
 * These pin the SQL shape and, above all, that user input only ever travels as a bound
 * argument. The device test in `VaultRepositoryQueryTest` proves the statements actually
 * return the right rows against SQLCipher.
 */
class VaultQueryTest {

    @Test
    fun `the default view excludes trash and archive`() {
        val statement = VaultQuery.build(VaultFilter())

        assertThat(statement.sql).contains("(trashed = 0 AND archived = 0)")
        assertThat(statement.args).isEmpty()
    }

    @Test
    fun `favorites exclude trash and archive`() {
        assertThat(VaultQuery.build(VaultFilter(smartList = SmartList.FAVORITES)).sql)
            .contains("(trashed = 0 AND archived = 0 AND favorite = 1)")
    }

    @Test
    fun `archive excludes trashed items`() {
        assertThat(VaultQuery.build(VaultFilter(smartList = SmartList.ARCHIVE)).sql)
            .contains("(trashed = 0 AND archived = 1)")
    }

    @Test
    fun `trash is ordered most recent first`() {
        val sql = VaultQuery.build(VaultFilter(smartList = SmartList.TRASH)).sql

        assertThat(sql).contains("(trashed = 1)")
        assertThat(sql).contains("ORDER BY updated_at DESC")
    }

    @Test
    fun `other lists are ordered by title`() {
        assertThat(VaultQuery.build(VaultFilter()).sql)
            .contains("ORDER BY title COLLATE NOCASE ASC")
    }

    @Test
    fun `template binds its stable id`() {
        val statement = VaultQuery.build(VaultFilter(template = Template.BANK_ACCOUNT))

        assertThat(statement.sql).contains("template = ?")
        assertThat(statement.args).containsExactly("bank_account")
    }

    @Test
    fun `tags require every selected tag`() {
        val statement = VaultQuery.build(VaultFilter(tagIds = setOf(9, 3)))

        assertThat(statement.sql).contains("tag_id IN (?,?)")
        assertThat(statement.sql).contains("HAVING COUNT(DISTINCT tag_id) = ?")
        assertThat(statement.args).containsExactly(3L, 9L, 2)
    }

    @Test
    fun `search uses the FTS index with a bound, sanitised expression`() {
        val statement = VaultQuery.build(VaultFilter(query = "my bank"))

        assertThat(statement.sql).contains("items_fts MATCH ?")
        assertThat(statement.args).containsExactly("\"my\"* \"bank\"*")
    }

    @Test
    fun `a blank search adds no condition`() {
        val statement = VaultQuery.build(VaultFilter(query = "   "))

        assertThat(statement.sql).doesNotContain("MATCH")
        assertThat(statement.args).isEmpty()
    }

    @Test
    fun `search input never reaches the SQL text`() {
        val hostile = "x') OR 1=1; DROP TABLE items; --"

        listOf(SmartList.ALL, SmartList.TRASH).forEach { list ->
            val statement = VaultQuery.build(VaultFilter(smartList = list, query = hostile))
            assertThat(statement.sql).doesNotContain("DROP")
            assertThat(statement.sql).doesNotContain("1=1")
        }
    }

    @Test
    fun `trash search matches title or subtitle per token`() {
        val statement = VaultQuery.build(VaultFilter(smartList = SmartList.TRASH, query = "old bank"))

        assertThat(statement.sql).doesNotContain("items_fts")
        assertThat(statement.args).containsExactly("%old%", "%old%", "%bank%", "%bank%")
    }

    @Test
    fun `a punctuation-only trash search adds no condition`() {
        val statement = VaultQuery.build(VaultFilter(smartList = SmartList.TRASH, query = "% _"))

        assertThat(statement.sql).doesNotContain("LIKE")
        assertThat(statement.args).isEmpty()
    }

    @Test
    fun `trash search escapes LIKE wildcards`() {
        val statement = VaultQuery.build(VaultFilter(smartList = SmartList.TRASH, query = "100%_a\\b"))

        assertThat(statement.args.first()).isEqualTo("%100\\%\\_a\\\\b%")
    }

    @Test
    fun `dimensions compose with AND in argument order`() {
        val statement = VaultQuery.build(
            VaultFilter(
                smartList = SmartList.FAVORITES,
                template = Template.LOGIN,
                tagIds = setOf(4),
                query = "mail",
            ),
        )

        assertThat(statement.args).containsExactly("login", 4L, 1, "\"mail\"*")
        assertThat(statement.sql.split(") AND (").size).isEqualTo(4)
    }

    @Test
    fun `smart lists that need audit or usage data are refused`() {
        listOf(SmartList.WEAK, SmartList.REUSED, SmartList.BREACHED, SmartList.RECENTLY_USED)
            .forEach { list ->
                assertThat(runCatching { VaultQuery.build(VaultFilter(smartList = list)) })
                    .isFailure()
                    .isInstanceOf(IllegalArgumentException::class)
            }
    }
}
