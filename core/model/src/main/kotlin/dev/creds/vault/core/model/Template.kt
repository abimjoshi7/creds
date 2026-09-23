package dev.creds.vault.core.model

/**
 * What an item *is*. A template decides which fields the editor offers and how autofill
 * interprets them.
 *
 * Templates are built-in and closed. How a user *files* an item is a [Tag], which is a
 * separate, open, many-to-many concern — renaming a tag must never alter item structure.
 */
enum class Template(val id: String) {
    LOGIN("login"),
    CARD("card"),
    BANK_ACCOUNT("bank_account"),
    NOTE("note"),
    DOCUMENT("document"),
    WIFI("wifi"),
    IDENTITY("identity"),
    PASSPORT("passport"),
    SERVER("server"),
    MISC("misc"),
    ;

    companion object {
        private val byId = entries.associateBy(Template::id)

        /** Unknown ids fall back to [MISC] so an import never drops an item. */
        fun fromId(id: String): Template = byId[id] ?: MISC
    }
}
