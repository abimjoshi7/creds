package dev.creds.vault.core.domain.autofill

import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.domain.totp.Totp
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem

/**
 * Which value from an item goes into which input.
 *
 * Only roles the item can actually answer get a value; a login without an email leaves
 * the email box alone rather than putting the username in it, unless the username is an
 * email address. `NEW_PASSWORD` is never filled: putting an old password into a
 * change-password form is exactly the wrong fill.
 */
object FillPlanner {

    fun <K> values(roles: Map<K, FieldRole>, item: VaultItem, now: Long): Map<K, String> {
        val fields = item.fields.filter { !it.deleted && it.type.isValueBearing && it.value.isNotBlank() }
        val result = LinkedHashMap<K, String>()
        for ((key, role) in roles) {
            valueFor(role, item.template, fields, now)?.let { result[key] = it }
        }
        return result
    }

    /** Whether [item] has anything to offer a form of these [kinds]. */
    fun offers(item: VaultItem, kinds: Set<FormKind>): Boolean {
        val types = item.fields.filter { !it.deleted && it.value.isNotBlank() }.map { it.type }.toSet()
        return kinds.any { kind ->
            when (kind) {
                FormKind.LOGIN -> FieldType.PASSWORD in types || FieldType.USERNAME in types || FieldType.EMAIL in types
                FormKind.OTP -> FieldType.TOTP in types
                FormKind.CARD -> item.template == Template.CARD
                FormKind.IDENTITY -> item.template == Template.IDENTITY
            }
        }
    }

    private fun valueFor(role: FieldRole, template: Template, fields: List<VaultField>, now: Long): String? {
        fun first(type: FieldType) = fields.firstOrNull { it.type == type }?.value
        val username = first(FieldType.USERNAME)
        val email = first(FieldType.EMAIL)

        return when (role) {
            FieldRole.USERNAME -> username ?: email
            FieldRole.EMAIL -> email ?: username?.takeIf { it.contains('@') }
            FieldRole.PASSWORD -> first(FieldType.PASSWORD)
            FieldRole.NEW_PASSWORD -> null
            FieldRole.OTP -> first(FieldType.TOTP)?.let(Totp::parse)?.let { Totp.generate(it, now).value }
            FieldRole.CARD_NUMBER -> first(FieldType.CARD_NUMBER)?.let(::cardNumber)
            FieldRole.CARD_EXPIRY -> first(FieldType.CARD_EXPIRY)?.let(::parseExpiry)?.let { (month, year) -> "%02d/%02d".format(month, year % 100) }
            FieldRole.CARD_EXPIRY_MONTH -> first(FieldType.CARD_EXPIRY)?.let(::parseExpiry)?.let { "%02d".format(it.first) }
            FieldRole.CARD_EXPIRY_YEAR -> first(FieldType.CARD_EXPIRY)?.let(::parseExpiry)?.let { "%04d".format(it.second) }
            FieldRole.CARD_CVC -> first(FieldType.CARD_CVC)
            FieldRole.CARD_HOLDER -> first(FieldType.CARD_HOLDER)
            FieldRole.NAME -> if (template == Template.IDENTITY || template == Template.PASSPORT) first(FieldType.TEXT) else first(FieldType.CARD_HOLDER)
            FieldRole.PHONE -> first(FieldType.PHONE)
            FieldRole.POSTAL_ADDRESS -> first(FieldType.MULTILINE)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")
        }
    }

    /** Digits only when the value is a plausible card number; as stored otherwise. */
    internal fun cardNumber(value: String): String {
        val digits = value.filter { it.isDigit() }
        return if (digits.length in 12..19 && value.all { it.isDigit() || it == ' ' || it == '-' }) digits else value
    }

    /**
     * Month and four-digit year from how people write expiry dates: `MM/YY`, `MM/YYYY`,
     * `MM-YY`, `MMYY`, `YYYY-MM`. Null when it is none of those.
     */
    internal fun parseExpiry(value: String): Pair<Int, Int>? {
        val trimmed = value.trim()
        val iso = Regex("""^(\d{4})\s*[-/.]\s*(\d{1,2})$""").find(trimmed)
        val common = Regex("""^(\d{1,2})\s*[-/.\s]\s*(\d{2}|\d{4})$""").find(trimmed)
        val compact = Regex("""^(\d{2})(\d{2})$""").find(trimmed)
        val (month, year) = when {
            iso != null -> iso.groupValues[2].toInt() to iso.groupValues[1].toInt()
            common != null -> common.groupValues[1].toInt() to common.groupValues[2].toInt()
            compact != null -> compact.groupValues[1].toInt() to compact.groupValues[2].toInt()
            else -> return null
        }
        if (month !in 1..12) return null
        return month to if (year < 100) 2000 + year else year
    }
}

/** A login captured from a form the user submitted. */
data class CapturedLogin(val username: String?, val password: String)

object SavePlanner {

    /**
     * The login to offer saving, or null when the form did not contain a password. A new
     * password wins over the current one: on a change-password form, the new one is what
     * the site will expect next time.
     */
    fun <K> capture(roles: Map<K, FieldRole>, values: Map<K, String>): CapturedLogin? {
        fun valueOf(role: FieldRole) = roles.entries.firstOrNull { it.value == role && !values[it.key].isNullOrBlank() }?.let { values[it.key] }
        val password = valueOf(FieldRole.NEW_PASSWORD) ?: valueOf(FieldRole.PASSWORD) ?: return null
        val username = valueOf(FieldRole.USERNAME) ?: valueOf(FieldRole.EMAIL)
        return CapturedLogin(username?.trim(), password)
    }

    /** A new login item for [captured], filed under the site or app it came from. */
    fun newItem(
        uuid: String,
        captured: CapturedLogin,
        title: String,
        website: String?,
        now: Long,
    ): VaultItem {
        val username = captured.username.orEmpty()
        val usernameType = if (username.contains('@')) FieldType.EMAIL else FieldType.USERNAME
        val fields = TemplateCatalog.newFields(Template.LOGIN, now).map { field ->
            when {
                field.type == usernameType -> field.copy(value = username)
                field.type == FieldType.PASSWORD -> field.copy(value = captured.password)
                field.type == FieldType.URL && website != null -> field.copy(value = website)
                else -> field
            }
        }.filter { it.value.isNotEmpty() }
        return VaultItem(
            uuid = uuid,
            template = Template.LOGIN,
            title = title,
            subtitle = TemplateCatalog.subtitleFor(Template.LOGIN, fields),
            createdAt = now,
            updatedAt = now,
            fields = fields,
        )
    }
}
