package dev.creds.vault.core.domain.strength

/**
 * Scores a password.
 *
 * Deliberately an interface with no implementation in this module: the Android build
 * backs it with zxcvbn, and an iOS build would back it with a Swift equivalent, while
 * every caller — the generator readout, the audit engine, the setup screen's master
 * password meter — depends only on this contract.
 *
 * Implementations take a [CharArray] so the caller can zero the buffer afterwards.
 * They must not retain it, log it, or copy it into a String.
 */
fun interface PasswordStrengthEstimator {
    fun estimate(password: CharArray): PasswordStrength
}

/**
 * @param score 0 (trivially guessable) to 4 (strong), matching the zxcvbn scale.
 * @param guessesLog10 base-10 log of the estimated guesses to crack. This is the value
 *   to compare and store; the raw guess count overflows.
 * @param crackTimeDisplay human-readable estimate for an offline slow-hash attack.
 * @param warning the single biggest problem, empty when there is none.
 * @param suggestions concrete improvements, ordered by impact.
 */
data class PasswordStrength(
    val score: Int,
    val guessesLog10: Double,
    val crackTimeDisplay: String,
    val warning: String = "",
    val suggestions: List<String> = emptyList(),
) {
    val band: StrengthBand get() = StrengthBand.of(score)

    companion object {
        /** Used for empty or absent values, which the audit reports separately. */
        val EMPTY = PasswordStrength(
            score = 0,
            guessesLog10 = 0.0,
            crackTimeDisplay = "instantly",
            warning = "No password set",
        )
    }
}

enum class StrengthBand {
    VERY_WEAK,
    WEAK,
    FAIR,
    STRONG,
    VERY_STRONG,
    ;

    companion object {
        fun of(score: Int): StrengthBand = when (score.coerceIn(0, 4)) {
            0 -> VERY_WEAK
            1 -> WEAK
            2 -> FAIR
            3 -> STRONG
            else -> VERY_STRONG
        }
    }
}

/** The threshold the audit treats as "weak enough to flag". */
const val WEAK_SCORE_THRESHOLD: Int = 2
