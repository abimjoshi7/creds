package dev.creds.vault.core.domain.generator

/** The three things the generator can make. */
enum class GeneratorMode { RANDOM, PASSPHRASE, PIN }

/** A character class for random passwords. */
enum class CharacterClass(val characters: String) {
    LOWERCASE("abcdefghijklmnopqrstuvwxyz"),
    UPPERCASE("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    DIGITS("0123456789"),

    /**
     * Symbols almost every site accepts. Quotes, backslash, backtick and space are left
     * out: they are the ones that break sign-up forms, shell pastes and CSV exports.
     */
    SYMBOLS("!#$%&()*+,-./:;<=>?@[]^_{|}~"),
    ;

    fun characters(excludeAmbiguous: Boolean): String =
        if (excludeAmbiguous) characters.filterNot { it in AMBIGUOUS } else characters

    companion object {
        /** Characters that read alike in many fonts: `l I 1 O 0`. */
        const val AMBIGUOUS: String = "lI1O0"
    }
}

/**
 * Options for a random password.
 *
 * Out-of-range values are clamped rather than rejected, because they arrive from a slider
 * and from stored preferences written by an older version.
 */
data class RandomOptions(
    val length: Int = DEFAULT_LENGTH,
    val classes: Set<CharacterClass> = CharacterClass.entries.toSet(),
    val excludeAmbiguous: Boolean = false,
    val requireEachClass: Boolean = true,
) {
    /** Never empty: switching every class off leaves lowercase on rather than nothing. */
    val effectiveClasses: Set<CharacterClass>
        get() = classes.ifEmpty { setOf(CharacterClass.LOWERCASE) }

    val effectiveLength: Int get() = length.coerceIn(MIN_LENGTH, MAX_LENGTH)

    companion object {
        const val MIN_LENGTH: Int = 8
        const val MAX_LENGTH: Int = 128
        const val DEFAULT_LENGTH: Int = 20
    }
}

enum class Capitalization { LOWER, TITLE, UPPER }

/**
 * Options for a passphrase.
 *
 * Capitalisation and the separator are applied the same way to every word, so they make
 * a passphrase easier to accept on a fussy site without adding any entropy — and the
 * readout does not pretend otherwise. Only the injected digit adds any.
 */
data class PassphraseOptions(
    val words: Int = DEFAULT_WORDS,
    val separator: String = "-",
    val capitalization: Capitalization = Capitalization.LOWER,
    val includeDigit: Boolean = false,
) {
    val effectiveWords: Int get() = words.coerceIn(MIN_WORDS, MAX_WORDS)

    companion object {
        const val MIN_WORDS: Int = 3
        const val MAX_WORDS: Int = 20

        /** Six words is EFF's own recommendation: about 77.5 bits. */
        const val DEFAULT_WORDS: Int = 6
    }
}

data class PinOptions(val length: Int = DEFAULT_LENGTH) {
    val effectiveLength: Int get() = length.coerceIn(MIN_LENGTH, MAX_LENGTH)

    companion object {
        const val MIN_LENGTH: Int = 4
        const val MAX_LENGTH: Int = 16
        const val DEFAULT_LENGTH: Int = 6
    }
}

/** Everything the generator remembers between uses. None of it is secret. */
data class GeneratorSettings(
    val mode: GeneratorMode = GeneratorMode.RANDOM,
    val random: RandomOptions = RandomOptions(),
    val passphrase: PassphraseOptions = PassphraseOptions(),
    val pin: PinOptions = PinOptions(),
)
