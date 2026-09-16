package dev.creds.vault.core.crypto.strength

import com.nulabinc.zxcvbn.Zxcvbn
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android implementation of the pure-Kotlin [PasswordStrengthEstimator] contract.
 *
 * Real pattern matching rather than a character-class checklist: zxcvbn knows about
 * dictionary words, keyboard walks, l33t substitution, dates and repeats, so
 * `P@ssw0rd123!` scores badly despite satisfying every "one upper, one digit, one
 * symbol" rule ever written.
 *
 * [Zxcvbn] loads its frequency dictionaries on construction, so it is a singleton.
 */
@Singleton
class ZxcvbnPasswordStrengthEstimator @Inject constructor(
    // No default value: a default argument makes Kotlin emit a second constructor, and
    // Dagger rejects a type with two @Inject constructors. CryptoModule provides this.
    private val zxcvbn: Zxcvbn,
) : PasswordStrengthEstimator {

    override fun estimate(password: CharArray): PasswordStrength {
        if (password.isEmpty()) return PasswordStrength.EMPTY

        // This String is unavoidable, and pretending otherwise is worse than admitting it.
        //
        // The interface takes a CharArray so callers can zero their buffer, and an earlier
        // version of this class passed a zero-copy CharSequence view over that array with a
        // redacted toString(). zxcvbn's matchers call toString() internally, so that view
        // made RepeatMatcher throw a NullPointerException on the first keystroke — the
        // wrapper bought no safety and crashed the setup screen.
        //
        // zxcvbn materialises a String from its argument regardless of what it is handed,
        // so the honest thing is to hand it one directly and keep the exposure minimal:
        // the String is local, never logged, never persisted, and unreachable once this
        // returns. The caller's CharArray is still theirs to wipe, and this never mutates it.
        val measured = zxcvbn.measure(String(password))
        val feedback = measured.feedback

        return PasswordStrength(
            score = measured.score,
            guessesLog10 = measured.guessesLog10,
            // The offline slow-hash number is the honest one to show: this vault is
            // Argon2id-sealed and stolen offline, not guessed through a login form.
            crackTimeDisplay = measured.crackTimesDisplay.offlineSlowHashing1e4perSecond,
            warning = feedback?.warning.orEmpty(),
            suggestions = feedback?.suggestions.orEmpty(),
        )
    }
}
