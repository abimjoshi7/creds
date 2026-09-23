package dev.creds.vault.items

import dev.creds.vault.core.domain.totp.Totp
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem

/** One row of the read-only item view. */
sealed interface DetailRow {
    val key: String

    data class Section(val label: String, override val key: String) : DetailRow

    data class Value(val field: VaultField) : DetailRow {
        override val key: String get() = "field:${this.field.uid}"
    }

    /** A TOTP field whose secret parses, shown as a live code rather than as the secret. */
    data class OneTimeCode(val field: VaultField) : DetailRow {
        override val key: String get() = "totp:${this.field.uid}"
    }
}

/**
 * What the item view shows, in order.
 *
 * Empty fields are left out — a template offers slots, and a login with no email should
 * not show a blank "Email". A section heading is shown only when something under it is,
 * so an imported item does not render a run of headings over nothing.
 */
fun VaultItem.detailRows(): List<DetailRow> {
    val rows = mutableListOf<DetailRow>()
    var pendingSection: DetailRow.Section? = null

    fields
        .filter { !it.deleted }
        .sortedBy { it.order }
        .forEach { field ->
            when {
                field.type == FieldType.SECTION ->
                    pendingSection = DetailRow.Section(field.label, key = "section:${field.uid}:${field.order}")

                field.value.isBlank() -> Unit

                else -> {
                    pendingSection?.let(rows::add)
                    pendingSection = null
                    val parses = field.type == FieldType.TOTP && Totp.parse(field.value) != null
                    rows += if (parses) DetailRow.OneTimeCode(field) else DetailRow.Value(field)
                }
            }
        }
    return rows
}
