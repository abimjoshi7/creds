package dev.creds.vault.core.model

/** Built-in views that are computed rather than filed into. */
enum class SmartList {
    ALL,
    FAVORITES,
    RECENTLY_USED,
    WEAK,
    REUSED,
    BREACHED,
    ARCHIVE,
    TRASH,
}

/**
 * A vault query. Template, tags and smart list compose with AND, so
 * `template=LOGIN AND tag=#work AND smartList=WEAK` is expressible.
 */
data class VaultFilter(
    val smartList: SmartList = SmartList.ALL,
    val template: Template? = null,
    val tagIds: Set<Long> = emptySet(),
    val query: String = "",
)
