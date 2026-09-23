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
 *
 * Tags are AND among themselves too: selecting `#work` and `#bank` narrows to items
 * carrying both. Filters only ever narrow, so adding one can never make an item appear
 * that was not already visible.
 */
data class VaultFilter(
    val smartList: SmartList = SmartList.ALL,
    val template: Template? = null,
    val tagIds: Set<Long> = emptySet(),
    val query: String = "",
) {
    /** Whether anything beyond the plain "all items" view is applied. */
    val isRefined: Boolean
        get() = smartList != SmartList.ALL || template != null || tagIds.isNotEmpty() || query.isNotBlank()

    /** Selecting the active template again clears it, like a toggle chip. */
    fun withTemplateToggled(template: Template): VaultFilter =
        copy(template = if (this.template == template) null else template)

    fun withTagToggled(tagId: Long): VaultFilter =
        copy(tagIds = if (tagId in tagIds) tagIds - tagId else tagIds + tagId)

    /**
     * Drops tag ids that no longer exist.
     *
     * A deleted tag left in the filter would AND against nothing and silently empty the
     * list, which looks exactly like data loss.
     */
    fun retainingTags(existing: Set<Long>): VaultFilter =
        if (existing.containsAll(tagIds)) this else copy(tagIds = tagIds intersect existing)
}

/** Item counts for the navigation drawer. Trashed items count only toward [trash]. */
data class VaultCounts(
    val all: Int = 0,
    val favorites: Int = 0,
    val archive: Int = 0,
    val trash: Int = 0,
    val weak: Int = 0,
    val reused: Int = 0,
    val breached: Int = 0,
    val byTemplate: Map<Template, Int> = emptyMap(),
    val byTag: Map<Long, Int> = emptyMap(),
) {
    /** Zero only when the vault holds nothing at all, trash and archive included. */
    val total: Int get() = all + archive + trash

    fun of(smartList: SmartList): Int? = when (smartList) {
        SmartList.ALL -> all
        SmartList.FAVORITES -> favorites
        SmartList.ARCHIVE -> archive
        SmartList.TRASH -> trash
        SmartList.WEAK -> weak
        SmartList.REUSED -> reused
        SmartList.BREACHED -> breached
        SmartList.RECENTLY_USED -> null
    }
}
