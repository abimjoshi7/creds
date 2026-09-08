package dev.creds.vault.core.model

/**
 * The type of a single field within an item.
 *
 * [defaultSensitive] decides the storage path, not just the presentation:
 * a sensitive value is encrypted with its own per-item key and is never written to the
 * searchable `search_text` column or the FTS index. That default can be overridden per
 * field — a user may mark a free-text field secret — but it must never be *weakened*
 * silently by an importer.
 */
enum class FieldType(
    val id: String,
    val defaultSensitive: Boolean = false,
) {
    USERNAME("username"),
    EMAIL("email"),
    PASSWORD("password", defaultSensitive = true),
    URL("url"),
    TOTP("totp", defaultSensitive = true),
    PIN("pin", defaultSensitive = true),
    PHONE("phone"),
    TEXT("text"),
    MULTILINE("multiline"),
    NUMERIC("numeric"),
    DATE("date"),

    /** A heading that groups the fields after it. Carries no value of its own. */
    SECTION("section"),

    CARD_NUMBER("ccNumber", defaultSensitive = true),
    CARD_CVC("ccCvc", defaultSensitive = true),
    CARD_PIN("ccPin", defaultSensitive = true),
    CARD_TXN_PASSWORD("ccTxnpassword", defaultSensitive = true),
    CARD_EXPIRY("ccExpiry"),
    CARD_HOLDER("ccName"),
    CARD_TYPE("ccType"),
    CARD_BANK("ccBankname"),
    ;

    /** Section headings hold no data, so they are neither stored nor audited as values. */
    val isValueBearing: Boolean get() = this != SECTION

    /** Only these participate in strength, reuse, breach and staleness checks. */
    val isAuditable: Boolean
        get() = this == PASSWORD || this == PIN || this == CARD_PIN || this == CARD_TXN_PASSWORD

    companion object {
        private val byId = entries.associateBy(FieldType::id)

        /** Unknown ids degrade to [TEXT] rather than failing an import. */
        fun fromId(id: String): FieldType = byId[id] ?: TEXT
    }
}
