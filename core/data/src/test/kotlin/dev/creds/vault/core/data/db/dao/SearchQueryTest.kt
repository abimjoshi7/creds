package dev.creds.vault.core.data.db.dao

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

/**
 * FTS5 query escaping.
 *
 * Unescaped user input is not a cosmetic problem here: in FTS5 a bare apostrophe or
 * quote is a syntax error that throws, and bare words like `AND` or `NEAR` are
 * operators. Search must never crash on a name with punctuation in it.
 */
class SearchQueryTest {

    @Test
    fun `wraps tokens as quoted prefix terms`() {
        assertThat(SearchQuery.sanitize("bank")).isEqualTo("\"bank\"*")
    }

    @Test
    fun `joins multiple tokens`() {
        assertThat(SearchQuery.sanitize("my bank")).isEqualTo("\"my\"* \"bank\"*")
    }

    @Test
    fun `collapses runs of whitespace`() {
        assertThat(SearchQuery.sanitize("  my   bank  ")).isEqualTo("\"my\"* \"bank\"*")
    }

    @Test
    fun `quoting neutralises punctuation`() {
        // Inside double quotes FTS5 reads these literally instead of as operators or
        // as a syntax error.
        assertThat(SearchQuery.sanitize("o'brien")).isEqualTo("\"o'brien\"*")
        assertThat(SearchQuery.sanitize("bank.com")).isEqualTo("\"bank.com\"*")
        assertThat(SearchQuery.sanitize("a-b")).isEqualTo("\"a-b\"*")
    }

    @Test
    fun `embedded double quotes are doubled`() {
        // FTS5's own escape. A single quote character here would terminate the string
        // and turn the rest of the input into syntax.
        assertThat(SearchQuery.sanitize("say \"hi\"")).isEqualTo("\"say\"* \"\"\"hi\"\"\"*")
    }

    @Test
    fun `operators are neutralised as literals`() {
        assertThat(SearchQuery.sanitize("a AND b")).isEqualTo("\"a\"* \"AND\"* \"b\"*")
        assertThat(SearchQuery.sanitize("x NEAR y")).isEqualTo("\"x\"* \"NEAR\"* \"y\"*")
    }

    @Test
    fun `blank input yields no filter rather than no results`() {
        // An empty search box means "everything", not "nothing".
        assertThat(SearchQuery.sanitize("")).isNull()
        assertThat(SearchQuery.sanitize("   ")).isNull()
    }

    @Test
    fun `punctuation-only input yields no filter`() {
        // FTS5 would tokenise "," to nothing and match no rows, emptying the list.
        assertThat(SearchQuery.sanitize(",")).isNull()
        assertThat(SearchQuery.sanitize(" - . ")).isNull()
    }

    @Test
    fun `punctuation-only tokens are dropped from a real query`() {
        assertThat(SearchQuery.sanitize("- bank")).isEqualTo("\"bank\"*")
    }
}
