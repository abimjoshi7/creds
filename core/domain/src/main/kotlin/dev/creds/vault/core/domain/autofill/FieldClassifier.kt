package dev.creds.vault.core.domain.autofill

/** What an input on screen is for. */
enum class FieldRole {
    USERNAME,
    EMAIL,
    PASSWORD,

    /** A sign-up or change-password field: saved from, never filled with an old password. */
    NEW_PASSWORD,
    OTP,
    CARD_NUMBER,
    CARD_EXPIRY,
    CARD_EXPIRY_MONTH,
    CARD_EXPIRY_YEAR,
    CARD_CVC,
    CARD_HOLDER,
    NAME,
    PHONE,
    POSTAL_ADDRESS,
}

enum class FormKind { LOGIN, OTP, CARD, IDENTITY }

/**
 * One view from the screen being filled, reduced to what classification reads.
 *
 * Built by the autofill service from an `AssistStructure` node; kept free of Android types
 * so every rule below is tested on the host. [key] is whatever the caller uses to find the
 * view again (an `AutofillId`).
 */
data class ViewNodeInfo<K>(
    val key: K,
    /** Editable text the framework can fill. Anything else is never classified. */
    val isTextInput: Boolean,
    val autofillHints: List<String> = emptyList(),
    /** `android:inputType` bits. */
    val inputType: Int = 0,
    val idEntry: String? = null,
    val hint: String? = null,
    val htmlTag: String? = null,
    val htmlAttributes: Map<String, String> = emptyMap(),
    val isFocused: Boolean = false,
)

/**
 * Decides what each input on a screen is for.
 *
 * In order of how much they can be believed: the app's own autofill hints, the page's
 * HTML `autocomplete`, the HTML input type and Android input type, and last, words in the
 * view's id, name and hint text. Each step only fills in what earlier ones left unknown.
 * Anything that looks like a search box is left alone.
 */
object FieldClassifier {

    fun <K> classify(nodes: List<ViewNodeInfo<K>>): Map<K, FieldRole> {
        val roles = LinkedHashMap<K, FieldRole>()
        val inputs = nodes.filter { it.isTextInput && !looksLikeSearch(it) }

        for (node in inputs) {
            val role = fromHints(node.autofillHints)
                ?: fromAutocomplete(node.htmlAttributes["autocomplete"])
                ?: fromTypes(node)
                ?: fromKeywords(node)
            if (role != null) roles[node.key] = role
        }

        inferUsername(inputs, roles)
        return roles
    }

    fun kinds(roles: Collection<FieldRole>): Set<FormKind> = buildSet {
        if (roles.any { it == FieldRole.PASSWORD || it == FieldRole.NEW_PASSWORD || it == FieldRole.USERNAME }) add(FormKind.LOGIN)
        // An email box on its own is most often step one of a login.
        if (FieldRole.EMAIL in roles && FieldRole.NAME !in roles && FieldRole.POSTAL_ADDRESS !in roles) add(FormKind.LOGIN)
        if (FieldRole.OTP in roles) add(FormKind.OTP)
        if (roles.any { it.name.startsWith("CARD_") }) add(FormKind.CARD)
        if (roles.any { it == FieldRole.NAME || it == FieldRole.PHONE || it == FieldRole.POSTAL_ADDRESS }) add(FormKind.IDENTITY)
    }

    private fun fromHints(hints: List<String>): FieldRole? {
        for (hint in hints) {
            val h = hint.lowercase().replace("_", "").replace("-", "")
            val role = when {
                h == "off" || h == "no" -> null
                h.contains("newpassword") -> FieldRole.NEW_PASSWORD
                h.contains("password") -> FieldRole.PASSWORD
                h.contains("otp") || h.contains("onetimecode") -> FieldRole.OTP
                h.contains("email") -> FieldRole.EMAIL
                h.contains("username") -> FieldRole.USERNAME
                h.contains("creditcardnumber") -> FieldRole.CARD_NUMBER
                h.contains("creditcardsecuritycode") -> FieldRole.CARD_CVC
                h.contains("creditcardexpirationmonth") -> FieldRole.CARD_EXPIRY_MONTH
                h.contains("creditcardexpirationyear") -> FieldRole.CARD_EXPIRY_YEAR
                h.contains("creditcardexpiration") -> FieldRole.CARD_EXPIRY
                h.contains("phone") -> FieldRole.PHONE
                h.contains("postaladdress") -> FieldRole.POSTAL_ADDRESS
                h == "name" || h.contains("personname") -> FieldRole.NAME
                else -> null
            }
            if (role != null) return role
        }
        return null
    }

    /** The WHATWG autocomplete tokens; only the last token names the field. */
    private fun fromAutocomplete(value: String?): FieldRole? =
        when (value?.trim()?.lowercase()?.split(Regex("\\s+"))?.lastOrNull()) {
            "username" -> FieldRole.USERNAME
            "email" -> FieldRole.EMAIL
            "current-password" -> FieldRole.PASSWORD
            "new-password" -> FieldRole.NEW_PASSWORD
            "one-time-code" -> FieldRole.OTP
            "cc-number" -> FieldRole.CARD_NUMBER
            "cc-exp" -> FieldRole.CARD_EXPIRY
            "cc-exp-month" -> FieldRole.CARD_EXPIRY_MONTH
            "cc-exp-year" -> FieldRole.CARD_EXPIRY_YEAR
            "cc-csc" -> FieldRole.CARD_CVC
            "cc-name" -> FieldRole.CARD_HOLDER
            "name" -> FieldRole.NAME
            "tel" -> FieldRole.PHONE
            "street-address" -> FieldRole.POSTAL_ADDRESS
            else -> null
        }

    private fun fromTypes(node: ViewNodeInfo<*>): FieldRole? {
        when (node.htmlAttributes["type"]?.lowercase()) {
            "password" -> return FieldRole.PASSWORD
            "email" -> return FieldRole.EMAIL
            "tel" -> return FieldRole.PHONE
        }
        val cls = node.inputType and TYPE_MASK_CLASS
        val variation = node.inputType and TYPE_MASK_VARIATION
        return when {
            cls == TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS -> FieldRole.PASSWORD
            cls == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD -> FieldRole.PASSWORD
            cls == TYPE_CLASS_TEXT && variation in EMAIL_VARIATIONS -> FieldRole.EMAIL
            cls == TYPE_CLASS_PHONE -> FieldRole.PHONE
            else -> null
        }
    }

    private fun fromKeywords(node: ViewNodeInfo<*>): FieldRole? {
        val text = descriptors(node)
        if (text.isEmpty()) return null
        return when {
            text.containsAny("otp", "one-time", "onetime", "one time", "totp", "2fa", "mfa", "verification code", "authenticator", "auth code") -> FieldRole.OTP
            text.containsAny("new password", "newpassword", "new_password", "confirm password", "confirmpassword", "repeat password") -> FieldRole.NEW_PASSWORD
            text.containsAny("password", "passwd", "pwd", "passwort", "contraseña", "mot de passe") -> FieldRole.PASSWORD
            text.containsAny("cardnumber", "card number", "card_number", "ccnumber", "cc-number", "cc_number") -> FieldRole.CARD_NUMBER
            text.containsAny("cvc", "cvv", "csc", "security code", "securitycode") -> FieldRole.CARD_CVC
            text.containsAny("exp_month", "expmonth", "expiry month", "expiration month") -> FieldRole.CARD_EXPIRY_MONTH
            text.containsAny("exp_year", "expyear", "expiry year", "expiration year") -> FieldRole.CARD_EXPIRY_YEAR
            text.containsAny("expiry", "expiration", "exp_date", "expdate", "mm/yy") -> FieldRole.CARD_EXPIRY
            text.containsAny("cardholder", "card holder", "name on card", "nameoncard") -> FieldRole.CARD_HOLDER
            text.containsAny("email", "e-mail") -> FieldRole.EMAIL
            text.containsAny("username", "user name", "user_name", "userid", "user id", "login", "log in", "signin", "sign in", "account") -> FieldRole.USERNAME
            else -> null
        }
    }

    /**
     * A login screen with a password but no recognisable username: the username is almost
     * always the last unclassified text input before the password.
     */
    private fun <K> inferUsername(inputs: List<ViewNodeInfo<K>>, roles: MutableMap<K, FieldRole>) {
        if (roles.values.any { it == FieldRole.USERNAME || it == FieldRole.EMAIL }) return
        val passwordIndex = inputs.indexOfFirst { roles[it.key] == FieldRole.PASSWORD }
        if (passwordIndex <= 0) return
        inputs.subList(0, passwordIndex).lastOrNull { it.key !in roles }?.let { roles[it.key] = FieldRole.USERNAME }
    }

    private fun looksLikeSearch(node: ViewNodeInfo<*>): Boolean =
        node.htmlAttributes["type"].equals("search", ignoreCase = true) ||
            descriptors(node).containsAny("search", "query")

    private fun descriptors(node: ViewNodeInfo<*>): String = listOfNotNull(
        node.idEntry,
        node.hint,
        node.htmlAttributes["name"],
        node.htmlAttributes["id"],
        node.htmlAttributes["label"],
        node.htmlAttributes["aria-label"],
        node.htmlAttributes["placeholder"],
    ).joinToString(" ").lowercase()

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    // android.text.InputType, restated so this module stays free of android.*.
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_CLASS_PHONE = 0x00000003
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    private val PASSWORD_VARIATIONS = setOf(0x80, 0x90, 0xe0) // password, visible, web
    private val EMAIL_VARIATIONS = setOf(0x20, 0xd0) // email address, web email address
}
