package dev.creds.vault.core.domain.autofill

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.creds.vault.core.domain.totp.Totp
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import org.junit.jupiter.api.Test

class FieldClassifierTest {

    @Test
    fun `autofill hints win over everything else`() {
        val roles = FieldClassifier.classify(
            listOf(
                node(1, hints = listOf("emailAddress"), idEntry = "password_field"),
                node(2, hints = listOf("password")),
                node(3, hints = listOf("2faAppOTPCode")),
            ),
        )

        assertThat(roles).isEqualTo(mapOf(1 to FieldRole.EMAIL, 2 to FieldRole.PASSWORD, 3 to FieldRole.OTP))
    }

    @Test
    fun `html autocomplete uses its last token`() {
        val roles = FieldClassifier.classify(
            listOf(
                node(1, html = mapOf("autocomplete" to "section-login username")),
                node(2, html = mapOf("autocomplete" to "shipping new-password")),
                node(3, html = mapOf("autocomplete" to "cc-exp-month")),
            ),
        )

        assertThat(roles).isEqualTo(mapOf(1 to FieldRole.USERNAME, 2 to FieldRole.NEW_PASSWORD, 3 to FieldRole.CARD_EXPIRY_MONTH))
    }

    @Test
    fun `input types identify passwords and emails`() {
        val roles = FieldClassifier.classify(
            listOf(
                node(1, inputType = 0x21), // text, email address
                node(2, inputType = 0x81), // text, password
                node(3, inputType = 0x12), // number, password
            ),
        )

        assertThat(roles).isEqualTo(mapOf(1 to FieldRole.EMAIL, 2 to FieldRole.PASSWORD, 3 to FieldRole.PASSWORD))
    }

    @Test
    fun `keywords are the last resort`() {
        val roles = FieldClassifier.classify(
            listOf(
                node(1, idEntry = "login_user_id"),
                node(2, hint = "Password"),
                node(3, html = mapOf("name" to "cardNumber")),
                node(4, html = mapOf("placeholder" to "CVV")),
                node(5, hint = "Enter verification code"),
            ),
        )

        assertThat(roles).isEqualTo(
            mapOf(1 to FieldRole.USERNAME, 2 to FieldRole.PASSWORD, 3 to FieldRole.CARD_NUMBER, 4 to FieldRole.CARD_CVC, 5 to FieldRole.OTP),
        )
    }

    @Test
    fun `the username is inferred as the text input before the password`() {
        val roles = FieldClassifier.classify(
            listOf(node(1, idEntry = "field_a"), node(2, idEntry = "field_b"), node(3, inputType = 0x81), node(4, idEntry = "field_c")),
        )

        assertThat(roles).isEqualTo(mapOf(3 to FieldRole.PASSWORD, 2 to FieldRole.USERNAME))
    }

    @Test
    fun `search boxes and non-text views are ignored`() {
        val roles = FieldClassifier.classify(
            listOf(
                node(1, html = mapOf("type" to "search", "name" to "username")),
                node(2, idEntry = "search_query_email"),
                node(3, hints = listOf("password"), isTextInput = false),
            ),
        )

        assertThat(roles).isEqualTo(emptyMap())
    }

    @Test
    fun `form kinds`() {
        assertThat(FieldClassifier.kinds(listOf(FieldRole.USERNAME, FieldRole.PASSWORD))).isEqualTo(setOf(FormKind.LOGIN))
        assertThat(FieldClassifier.kinds(listOf(FieldRole.EMAIL))).isEqualTo(setOf(FormKind.LOGIN))
        assertThat(FieldClassifier.kinds(listOf(FieldRole.OTP))).isEqualTo(setOf(FormKind.OTP))
        assertThat(FieldClassifier.kinds(listOf(FieldRole.CARD_NUMBER, FieldRole.CARD_CVC))).isEqualTo(setOf(FormKind.CARD))
        assertThat(FieldClassifier.kinds(listOf(FieldRole.NAME, FieldRole.EMAIL, FieldRole.POSTAL_ADDRESS))).isEqualTo(setOf(FormKind.IDENTITY))
        assertThat(FieldClassifier.kinds(emptyList())).isEmpty()
    }

    @Test
    fun `a login fills username, password and the current code, never a new password`() {
        val item = item(
            Template.LOGIN,
            VaultField(1, FieldType.USERNAME, "Username", "alice"),
            VaultField(2, FieldType.PASSWORD, "Password", "hunter2-Long"),
            VaultField(3, FieldType.TOTP, "Code", "JBSWY3DPEHPK3PXP"),
        )
        val roles = mapOf(10 to FieldRole.USERNAME, 11 to FieldRole.PASSWORD, 12 to FieldRole.OTP, 13 to FieldRole.NEW_PASSWORD, 14 to FieldRole.EMAIL)

        val values = FillPlanner.values(roles, item, now = 59_000)

        val code = Totp.generate(Totp.parse("JBSWY3DPEHPK3PXP")!!, 59_000).value
        assertThat(values).isEqualTo(mapOf(10 to "alice", 11 to "hunter2-Long", 12 to code))
    }

    @Test
    fun `an email username fills an email box`() {
        val item = item(Template.LOGIN, VaultField(1, FieldType.USERNAME, "Username", "alice@example.com"))

        assertThat(FillPlanner.values(mapOf(1 to FieldRole.EMAIL), item, 0)).isEqualTo(mapOf(1 to "alice@example.com"))
    }

    @Test
    fun `cards fill normalised numbers and expiry in every shape`() {
        val card = item(
            Template.CARD,
            VaultField(1, FieldType.CARD_NUMBER, "Number", "4111 1111 1111 1111"),
            VaultField(2, FieldType.CARD_EXPIRY, "Expiry", "7/2031"),
            VaultField(3, FieldType.CARD_CVC, "CVC", "123"),
            VaultField(4, FieldType.CARD_HOLDER, "Holder", "A Person"),
        )
        val roles = mapOf(
            1 to FieldRole.CARD_NUMBER, 2 to FieldRole.CARD_EXPIRY, 3 to FieldRole.CARD_EXPIRY_MONTH,
            4 to FieldRole.CARD_EXPIRY_YEAR, 5 to FieldRole.CARD_CVC, 6 to FieldRole.CARD_HOLDER, 7 to FieldRole.NAME,
        )

        assertThat(FillPlanner.values(roles, card, 0)).isEqualTo(
            mapOf(1 to "4111111111111111", 2 to "07/31", 3 to "07", 4 to "2031", 5 to "123", 6 to "A Person", 7 to "A Person"),
        )
    }

    @Test
    fun `expiry parsing`() {
        assertThat(FillPlanner.parseExpiry("12/25")).isEqualTo(12 to 2025)
        assertThat(FillPlanner.parseExpiry("0128")).isEqualTo(1 to 2028)
        assertThat(FillPlanner.parseExpiry("2030-03")).isEqualTo(3 to 2030)
        assertThat(FillPlanner.parseExpiry("13/25")).isNull()
        assertThat(FillPlanner.parseExpiry("soon")).isNull()
    }

    @Test
    fun `saving prefers the new password and needs one`() {
        val roles = mapOf(1 to FieldRole.EMAIL, 2 to FieldRole.PASSWORD, 3 to FieldRole.NEW_PASSWORD)

        assertThat(SavePlanner.capture(roles, mapOf(1 to " a@b.c ", 2 to "old", 3 to "new")))
            .isEqualTo(CapturedLogin("a@b.c", "new"))
        assertThat(SavePlanner.capture(roles, mapOf(1 to "a@b.c", 3 to ""))).isNull()
    }

    @Test
    fun `a saved login files the username by shape and keeps the website`() {
        val item = SavePlanner.newItem("u", CapturedLogin("a@b.c", "pw-Long-1"), "bank.com", "https://bank.com", now = 9)

        assertThat(item.fields.map { it.type to it.value }).containsExactly(
            FieldType.EMAIL to "a@b.c",
            FieldType.PASSWORD to "pw-Long-1",
            FieldType.URL to "https://bank.com",
        )
        assertThat(item.subtitle).isEqualTo("a@b.c")
    }

    private fun node(
        key: Int,
        hints: List<String> = emptyList(),
        inputType: Int = 0x1,
        idEntry: String? = null,
        hint: String? = null,
        html: Map<String, String> = emptyMap(),
        isTextInput: Boolean = true,
    ) = ViewNodeInfo(key, isTextInput, hints, inputType, idEntry, hint, htmlTag = if (html.isEmpty()) null else "input", htmlAttributes = html)

    private fun item(template: Template, vararg fields: VaultField) =
        VaultItem(uuid = "i", template = template, title = "t", createdAt = 0, updatedAt = 0, fields = fields.toList())
}
