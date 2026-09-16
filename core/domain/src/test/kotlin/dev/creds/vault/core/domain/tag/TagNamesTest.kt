package dev.creds.vault.core.domain.tag

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class TagNamesTest {

    @Test
    fun `trims and collapses whitespace`() {
        assertThat(TagNames.normalize("  side   project \t")).isEqualTo("side project")
    }

    @Test
    fun `drops the leading hash people type`() {
        assertThat(TagNames.normalize("#work")).isEqualTo("work")
        assertThat(TagNames.normalize("## work")).isEqualTo("work")
    }

    @Test
    fun `keeps a hash that is not leading`() {
        assertThat(TagNames.normalize("c#")).isEqualTo("c#")
    }

    @Test
    fun `blank and hash-only names are rejected`() {
        assertThat(TagNames.check("")).isEqualTo(TagNameCheck.Blank)
        assertThat(TagNames.check("   ")).isEqualTo(TagNameCheck.Blank)
        assertThat(TagNames.check("#")).isEqualTo(TagNameCheck.Blank)
    }

    @Test
    fun `accepts a name at the limit`() {
        val name = "a".repeat(TagNames.MAX_LENGTH)
        assertThat(TagNames.check(name)).isEqualTo(TagNameCheck.Valid(name))
    }

    @Test
    fun `rejects a name over the limit`() {
        assertThat(TagNames.check("a".repeat(TagNames.MAX_LENGTH + 1)))
            .isEqualTo(TagNameCheck.TooLong(TagNames.MAX_LENGTH))
    }

    @Test
    fun `length counts code points, not UTF-16 units`() {
        // An emoji is two chars; counting chars would reject a name a person sees as short.
        val name = "🔑".repeat(TagNames.MAX_LENGTH)
        assertThat(TagNames.check(name)).isEqualTo(TagNameCheck.Valid(name))
    }

    @Test
    fun `same name ignores case, spacing and hash`() {
        assertThat(TagNames.sameName("Work", " #work ")).isTrue()
        assertThat(TagNames.sameName("work", "works")).isFalse()
    }

    @Test
    fun `same name folds case beyond ASCII`() {
        // SQLite's NOCASE would call these different.
        assertThat(TagNames.sameName("Über", "über")).isTrue()
    }
}
