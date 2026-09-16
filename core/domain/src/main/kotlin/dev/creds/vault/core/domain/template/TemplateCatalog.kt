package dev.creds.vault.core.domain.template

import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField

/**
 * One field a template offers when a new item is created.
 *
 * [sensitive] defaults to the type's own default but can be raised: an account number is
 * stored as free text, yet it has no business in the search index.
 */
data class FieldSpec(
    val type: FieldType,
    val label: String,
    val sensitive: Boolean = type.defaultSensitive,
) {
    init {
        // A spec may make a field more secret than its type, never less. Weakening a
        // password to non-sensitive here would put every new one in the FTS index.
        require(sensitive || !type.defaultSensitive) {
            "$label: ${type.id} is sensitive and cannot be declared otherwise"
        }
    }
}

/**
 * What a template looks like: its name, the fields a new item starts with, and which of
 * those describe the item well enough to be its list subtitle.
 */
data class TemplateSpec(
    val template: Template,
    val displayName: String,
    val fields: List<FieldSpec>,
    /** Checked in order; the first non-empty, non-sensitive match becomes the subtitle. */
    val subtitleTypes: List<FieldType>,
)

/**
 * The built-in templates.
 *
 * Templates are closed (see [Template]), so this is a fixed table rather than something
 * stored. Changing a template's default fields affects only items created afterwards;
 * existing items keep whatever fields they were saved with.
 */
object TemplateCatalog {

    private val specs: Map<Template, TemplateSpec> = listOf(
        TemplateSpec(
            template = Template.LOGIN,
            displayName = "Login",
            fields = listOf(
                FieldSpec(FieldType.USERNAME, "Username"),
                FieldSpec(FieldType.EMAIL, "Email"),
                FieldSpec(FieldType.PASSWORD, "Password"),
                FieldSpec(FieldType.URL, "Website"),
                FieldSpec(FieldType.TOTP, "One-time code"),
            ),
            subtitleTypes = listOf(FieldType.USERNAME, FieldType.EMAIL, FieldType.URL),
        ),
        TemplateSpec(
            template = Template.CARD,
            displayName = "Payment card",
            fields = listOf(
                FieldSpec(FieldType.CARD_HOLDER, "Cardholder"),
                FieldSpec(FieldType.CARD_NUMBER, "Number"),
                FieldSpec(FieldType.CARD_EXPIRY, "Expiry"),
                FieldSpec(FieldType.CARD_CVC, "CVC"),
                FieldSpec(FieldType.CARD_PIN, "PIN"),
                FieldSpec(FieldType.CARD_TYPE, "Type"),
                FieldSpec(FieldType.CARD_BANK, "Issuing bank"),
            ),
            // Deliberately not a masked "•••• 1234": the subtitle is plaintext and indexed,
            // and the last four digits of a card number are part of a secret.
            subtitleTypes = listOf(FieldType.CARD_BANK, FieldType.CARD_HOLDER, FieldType.CARD_TYPE),
        ),
        TemplateSpec(
            template = Template.BANK_ACCOUNT,
            displayName = "Bank account",
            fields = listOf(
                FieldSpec(FieldType.TEXT, "Bank"),
                FieldSpec(FieldType.TEXT, "Account holder"),
                FieldSpec(FieldType.TEXT, "Account number", sensitive = true),
                FieldSpec(FieldType.TEXT, "Routing / IBAN", sensitive = true),
                FieldSpec(FieldType.USERNAME, "Online banking ID"),
                FieldSpec(FieldType.PASSWORD, "Password"),
                FieldSpec(FieldType.PIN, "PIN"),
                FieldSpec(FieldType.URL, "Website"),
            ),
            subtitleTypes = listOf(FieldType.TEXT, FieldType.USERNAME),
        ),
        TemplateSpec(
            template = Template.NOTE,
            displayName = "Secure note",
            // The note body is the item's own note, which is always encrypted.
            fields = emptyList(),
            subtitleTypes = emptyList(),
        ),
        TemplateSpec(
            template = Template.WIFI,
            displayName = "Wi-Fi",
            fields = listOf(
                FieldSpec(FieldType.TEXT, "Network name"),
                FieldSpec(FieldType.PASSWORD, "Password"),
                FieldSpec(FieldType.TEXT, "Security"),
            ),
            subtitleTypes = listOf(FieldType.TEXT),
        ),
        TemplateSpec(
            template = Template.IDENTITY,
            displayName = "Identity",
            fields = listOf(
                FieldSpec(FieldType.TEXT, "Full name"),
                FieldSpec(FieldType.EMAIL, "Email"),
                FieldSpec(FieldType.PHONE, "Phone"),
                FieldSpec(FieldType.MULTILINE, "Address"),
                FieldSpec(FieldType.DATE, "Date of birth"),
            ),
            subtitleTypes = listOf(FieldType.TEXT, FieldType.EMAIL),
        ),
        TemplateSpec(
            template = Template.PASSPORT,
            displayName = "Passport",
            fields = listOf(
                FieldSpec(FieldType.TEXT, "Full name"),
                FieldSpec(FieldType.TEXT, "Passport number", sensitive = true),
                FieldSpec(FieldType.TEXT, "Nationality"),
                FieldSpec(FieldType.DATE, "Issued"),
                FieldSpec(FieldType.DATE, "Expires"),
            ),
            subtitleTypes = listOf(FieldType.TEXT),
        ),
        TemplateSpec(
            template = Template.SERVER,
            displayName = "Server",
            fields = listOf(
                FieldSpec(FieldType.URL, "Host"),
                FieldSpec(FieldType.NUMERIC, "Port"),
                FieldSpec(FieldType.USERNAME, "Username"),
                FieldSpec(FieldType.PASSWORD, "Password"),
            ),
            subtitleTypes = listOf(FieldType.URL, FieldType.USERNAME),
        ),
        TemplateSpec(
            template = Template.MISC,
            displayName = "Other",
            fields = listOf(
                FieldSpec(FieldType.TEXT, "Text"),
            ),
            subtitleTypes = listOf(FieldType.USERNAME, FieldType.EMAIL, FieldType.TEXT),
        ),
    ).associateBy(TemplateSpec::template)

    init {
        check(specs.keys == Template.entries.toSet()) {
            "Missing template specs: ${Template.entries - specs.keys}"
        }
    }

    fun spec(template: Template): TemplateSpec = specs.getValue(template)

    fun displayName(template: Template): String = spec(template).displayName

    /**
     * The fields a brand-new item of this template starts with, in order.
     *
     * Values are empty; `uid` is 0 so the database assigns one.
     */
    fun newFields(template: Template, now: Long): List<VaultField> =
        spec(template).fields.mapIndexed { index, spec ->
            VaultField(
                uid = 0,
                type = spec.type,
                label = spec.label,
                value = "",
                sensitive = spec.sensitive,
                order = index,
                updatedAt = now,
                valueUpdatedAt = now,
            )
        }

    /**
     * The one-line description shown under an item's title.
     *
     * Only non-sensitive values are eligible, whatever the field type says. The subtitle
     * is stored as plaintext inside the database and written to the search index, so a
     * sensitive value reaching it would bypass the per-field encryption entirely.
     */
    fun subtitleFor(template: Template, fields: List<VaultField>): String {
        val eligible = fields
            .filter { !it.deleted && !it.sensitive && it.type.isValueBearing }
            .sortedBy { it.order }
            .filter { it.value.isNotBlank() }

        return spec(template).subtitleTypes.firstNotNullOfOrNull { type ->
            eligible.firstOrNull { it.type == type }
        }?.value?.lineSequence()?.first()?.trim().orEmpty()
    }
}
