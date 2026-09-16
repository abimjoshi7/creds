package dev.creds.vault.core.crypto.strength

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.nulabinc.zxcvbn.Zxcvbn
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.domain.strength.StrengthBand
import org.junit.jupiter.api.Test

/**
 * The strength estimator.
 *
 * These run on the host because zxcvbn is a plain JVM library — which is the point. An
 * earlier version of this class wrapped the password in a zero-copy `CharSequence` whose
 * `toString()` returned a redacted placeholder; zxcvbn's matchers call `toString()`
 * internally, so it threw a `NullPointerException` inside `RepeatMatcher` on the first
 * keystroke and took the whole setup screen down with it.
 *
 * Nothing caught that until the crash showed up on a device, because this class had no
 * tests at all. [doesNotThrowOnRepeatHeavyInput] covers the exact matcher that threw.
 */
class ZxcvbnPasswordStrengthEstimatorTest {

    private val estimator = ZxcvbnPasswordStrengthEstimator(Zxcvbn())

    @Test
    fun `empty password is reported as empty rather than scored`() {
        assertThat(estimator.estimate(CharArray(0))).isEqualTo(PasswordStrength.EMPTY)
    }

    @Test
    fun doesNotThrowOnRepeatHeavyInput() {
        // The regression. Every one of these drives zxcvbn's RepeatMatcher, which is
        // where the redacted-toString wrapper blew up.
        listOf("aaaaaa", "abcabcabc", "aaaaaaaaaaaaaaaaaaaa", "ababababab", "zzzz1111")
            .forEach { password ->
                val result = estimator.estimate(password.toCharArray())
                assertThat(result.crackTimeDisplay.isNotEmpty()).isTrue()
            }
    }

    @Test
    fun doesNotThrowOnTheOtherMatcherFamilies() {
        // Keyboard walks, dates, sequences, l33t and dictionary words each take their own
        // path through zxcvbn. A crash in any of them is a crash while someone types.
        listOf("qwertyuiop", "19850312", "abcdefgh", "p@ssw0rd", "correct horse", "!!!___***")
            .forEach { password ->
                val result = estimator.estimate(password.toCharArray())
                assertThat(result.crackTimeDisplay.isNotEmpty()).isTrue()
            }
    }

    @Test
    fun handlesNonAsciiWithoutThrowing() {
        listOf("pässwörtchen", "日本語のパスワード", "café-café-café", "🔐🔐🔐🔐")
            .forEach { password ->
                estimator.estimate(password.toCharArray())
            }
    }

    @Test
    fun `a trivial password scores at the bottom`() {
        val result = estimator.estimate("password".toCharArray())

        assertThat(result.score).isLessThanOrEqualTo(1)
        assertThat(result.band).isEqualTo(StrengthBand.VERY_WEAK)
    }

    @Test
    fun `a long passphrase scores well above a mangled short one`() {
        val passphrase = estimator.estimate("correct horse battery staple".toCharArray())
        val mangled = estimator.estimate("P@ssw0rd1".toCharArray())

        // The entire reason for using zxcvbn rather than a character-class checklist:
        // the mangled one satisfies every "one upper, one digit, one symbol" rule and is
        // still far easier to guess.
        assertThat(passphrase.guessesLog10).isGreaterThan(mangled.guessesLog10)
        assertThat(passphrase.score).isGreaterThan(mangled.score)
    }

    @Test
    fun `reports a crack time and feedback for a weak password`() {
        val result = estimator.estimate("qwerty".toCharArray())

        assertThat(result.crackTimeDisplay.isNotEmpty()).isTrue()
        assertThat(result.warning).isNotNull()
    }

    @Test
    fun `does not modify or consume the caller's buffer`() {
        val password = "hunter2".toCharArray()

        estimator.estimate(password)

        // The caller owns this array and still needs it — setup seals the vault key with
        // it after scoring.
        assertThat(String(password)).isEqualTo("hunter2")
    }

    @Test
    fun `scoring is deterministic`() {
        val first = estimator.estimate("correct horse battery staple".toCharArray())
        val second = estimator.estimate("correct horse battery staple".toCharArray())

        assertThat(first.score).isEqualTo(second.score)
        assertThat(first.guessesLog10).isEqualTo(second.guessesLog10)
    }
}
