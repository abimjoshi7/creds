package dev.creds.vault.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class VaultFilterTest {

    @Test
    fun `the default filter is not refined`() {
        assertThat(VaultFilter().isRefined).isFalse()
    }

    @Test
    fun `a whitespace query does not count as a refinement`() {
        assertThat(VaultFilter(query = "   ").isRefined).isFalse()
    }

    @Test
    fun `every dimension counts as a refinement`() {
        assertThat(VaultFilter(smartList = SmartList.TRASH).isRefined).isTrue()
        assertThat(VaultFilter(template = Template.CARD).isRefined).isTrue()
        assertThat(VaultFilter(tagIds = setOf(1)).isRefined).isTrue()
        assertThat(VaultFilter(query = "bank").isRefined).isTrue()
    }

    @Test
    fun `selecting the active template clears it`() {
        val filter = VaultFilter().withTemplateToggled(Template.LOGIN)

        assertThat(filter.template).isEqualTo(Template.LOGIN)
        assertThat(filter.withTemplateToggled(Template.LOGIN).template).isNull()
    }

    @Test
    fun `selecting another template replaces it`() {
        val filter = VaultFilter(template = Template.LOGIN).withTemplateToggled(Template.CARD)

        assertThat(filter.template).isEqualTo(Template.CARD)
    }

    @Test
    fun `tags toggle independently`() {
        val filter = VaultFilter().withTagToggled(1).withTagToggled(2).withTagToggled(1)

        assertThat(filter.tagIds).isEqualTo(setOf(2L))
    }

    @Test
    fun `deleted tags are dropped from the filter`() {
        val filter = VaultFilter(tagIds = setOf(1, 2, 3)).retainingTags(setOf(2, 3, 4))

        assertThat(filter.tagIds).isEqualTo(setOf(2L, 3L))
    }

    @Test
    fun `retaining existing tags returns the same instance`() {
        // Keeps a StateFlow from re-emitting, and re-querying, on every tag list change.
        val filter = VaultFilter(tagIds = setOf(1))

        assertThat(filter.retainingTags(setOf(1, 2))).isSameInstanceAs(filter)
    }

    @Test
    fun `total counts archive and trash but favorites only once`() {
        val counts = VaultCounts(all = 5, favorites = 2, archive = 1, trash = 3)

        assertThat(counts.total).isEqualTo(9)
    }
}
