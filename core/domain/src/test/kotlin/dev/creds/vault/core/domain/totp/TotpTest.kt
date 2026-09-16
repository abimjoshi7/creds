package dev.creds.vault.core.domain.totp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class TotpTest {

    // RFC 6238 Appendix B — ASCII secret "12345678901234567890"
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun rfc6238Sha1Vectors() {
        val cases = listOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        cases.forEach { (epochSeconds, expected) ->
            val code = Totp.hotp(rfcSecret, epochSeconds / 30, digits = 8, algorithm = "SHA1")
            assertThat(code, "t=$epochSeconds").isEqualTo(expected)
        }
    }

    @Test
    fun generateReportsRemainingSeconds() {
        val spec = Totp.Spec(secret = rfcSecret, digits = 8, periodSeconds = 30)
        // Exactly at a step boundary: remaining should be a full period.
        val code = Totp.generate(spec, epochMillis = 59_000L)
        assertThat(code.value).isEqualTo("94287082")
        assertThat(code.remainingSeconds).isEqualTo(1)
        assertThat(code.counter).isEqualTo(1L)
    }

    @Test
    fun parsesBase32Secrets() {
        // Base32 of "12345678901234567890"
        val spec = Totp.parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertThat(spec).isNotNull()
        assertThat(spec!!.secret.toString(Charsets.US_ASCII)).isEqualTo("12345678901234567890")
    }

    @Test
    fun parsesOtpauthUri() {
        val spec = Totp.parse(
            "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&digits=6&period=30&algorithm=SHA1",
        )
        assertThat(spec).isNotNull()
        assertThat(spec!!.issuer).isEqualTo("Example")
        assertThat(spec.account).isEqualTo("alice@example.com")
        assertThat(spec.digits).isEqualTo(6)
        assertThat(spec.periodSeconds).isEqualTo(30)
    }

    @Test
    fun blankOrGarbageIsNull() {
        assertThat(Totp.parse("")).isNull()
        assertThat(Totp.parse("   ")).isNull()
        assertThat(Totp.parse("not-base32!!!")).isNull()
        assertThat(Totp.parse("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP")).isNull()
    }
}
