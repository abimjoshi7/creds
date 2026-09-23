package dev.creds.vault.core.domain.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasLength
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.Random
import kotlin.math.log2

class PasswordGeneratorTest {

    private val generator = PasswordGenerator(UniformRandom(RandomSource(Random(7)::nextBytes)))

    @Test
    fun `random passwords use only the chosen classes, at the chosen length`() {
        repeat(200) {
            val options = RandomOptions(length = 32, classes = setOf(CharacterClass.LOWERCASE, CharacterClass.DIGITS))
            val value = generator.random(options).value

            assertThat(value).hasLength(32)
            assertThat(value.all { it in 'a'..'z' || it in '0'..'9' }).isTrue()
        }
    }

    @Test
    fun `every class is present when required`() {
        repeat(500) {
            val value = generator.random(RandomOptions(length = 8, requireEachClass = true)).value

            CharacterClass.entries.forEach { cls ->
                assertThat(value.any { it in cls.characters }, "missing ${cls.name} in $value").isTrue()
            }
        }
    }

    @Test
    fun `ambiguous characters can be excluded`() {
        repeat(200) {
            val value = generator.random(RandomOptions(length = 64, excludeAmbiguous = true)).value

            assertThat(value.none { it in CharacterClass.AMBIGUOUS }).isTrue()
        }
    }

    @Test
    fun `length and classes are clamped rather than refused`() {
        assertThat(generator.random(RandomOptions(length = 2)).value).hasLength(RandomOptions.MIN_LENGTH)
        assertThat(generator.random(RandomOptions(length = 999)).value).hasLength(RandomOptions.MAX_LENGTH)
        assertThat(generator.random(RandomOptions(classes = emptySet())).value.all { it in 'a'..'z' }).isTrue()
    }

    @Test
    fun `entropy without the requirement is length times log2 of the alphabet`() {
        val bits = generator.random(RandomOptions(length = 10, classes = setOf(CharacterClass.DIGITS))).entropyBits

        assertThat(bits).isCloseTo(10 * log2(10.0), 1e-9)
    }

    @Test
    fun `entropy with the requirement matches a brute-force count`() {
        // Alphabet {a,b} + {1,2,3}, length 4: count strings with at least one of each.
        val alphabet = "ab123"
        var count = 0
        for (i in 0 until 625) {
            val s = (0 until 4).map { alphabet[(i / pow(5, it)) % 5] }
            if (s.any { it in "ab" } && s.any { it in "123" }) count++
        }

        assertThat(PasswordGenerator.randomEntropyBits(listOf(2, 3), 4, requireEach = true))
            .isCloseTo(log2(count.toDouble()), 1e-9)
    }

    @Test
    fun `the requirement costs a little entropy, never adds any`() {
        val sizes = CharacterClass.entries.map { it.characters.length }
        fun cost(length: Int) = PasswordGenerator.randomEntropyBits(sizes, length, requireEach = false) -
            PasswordGenerator.randomEntropyBits(sizes, length, requireEach = true)

        // About a bit at the minimum length, where missing a class is common, and a small
        // fraction of one at the default (roughly one draw in nine lacks a digit).
        assertThat(cost(8)).isGreaterThan(0.0)
        assertThat(cost(8)).isLessThan(1.5)
        assertThat(cost(RandomOptions.DEFAULT_LENGTH)).isGreaterThan(0.0)
        assertThat(cost(RandomOptions.DEFAULT_LENGTH)).isLessThan(0.25)
    }

    @Test
    fun `log2 of a huge integer is accurate`() {
        assertThat(PasswordGenerator.log2(BigInteger.valueOf(94).pow(128))).isCloseTo(128 * log2(94.0), 1e-9)
        assertThat(PasswordGenerator.log2(BigInteger.ONE)).isCloseTo(0.0, 1e-12)
    }

    @Test
    fun `passphrases draw words from the list`() {
        val words = EffWordlist.words.toSet()
        val result = generator.passphrase(PassphraseOptions(words = 6, separator = " "))

        val parts = result.value.split(" ")
        assertThat(parts).hasSize(6)
        assertThat(parts.all { it in words }).isTrue()
        assertThat(result.entropyBits).isCloseTo(6 * log2(7776.0), 1e-9)
    }

    @Test
    fun `capitalisation applies to every word`() {
        val title = generator.passphrase(PassphraseOptions(words = 4, separator = ".", capitalization = Capitalization.TITLE))
        val upper = generator.passphrase(PassphraseOptions(words = 4, separator = ".", capitalization = Capitalization.UPPER))

        assertThat(title.value.split(".").all { it.first().isUpperCase() && it.drop(1) == it.drop(1).lowercase() }).isTrue()
        assertThat(upper.value).isEqualTo(upper.value.uppercase())
    }

    @Test
    fun `an injected digit lands on exactly one word and is counted`() {
        repeat(100) {
            val result = generator.passphrase(PassphraseOptions(words = 5, separator = "_", includeDigit = true))

            val withDigit = result.value.split("_").filter { it.last().isDigit() }
            assertThat(withDigit).hasSize(1)
            assertThat(result.entropyBits).isCloseTo(5 * log2(7776.0) + log2(5.0) + log2(10.0), 1e-9)
        }
    }

    @Test
    fun `pins are digits only`() {
        val result = generator.pin(PinOptions(length = 8))

        assertThat(result.value).hasLength(8)
        assertThat(result.value.all { it.isDigit() }).isTrue()
        assertThat(result.entropyBits).isCloseTo(8 * log2(10.0), 1e-9)
    }

    @Test
    fun `settings dispatch on mode`() {
        assertThat(generator.generate(GeneratorSettings(mode = GeneratorMode.PIN)).value.all { it.isDigit() }).isTrue()
        assertThat(generator.generate(GeneratorSettings(mode = GeneratorMode.PASSPHRASE)).value).contains("-")
    }

    @Test
    fun `the wordlist is the full, ordered EFF list`() {
        val words = EffWordlist.words

        assertThat(words).hasSize(7776)
        assertThat(words.first()).isEqualTo("abacus")
        assertThat(words.last()).isEqualTo("zoom")
        assertThat(words.toSet()).hasSize(7776)
    }

    @Test
    fun `dice indices run 11111 to 66666`() {
        assertThat(listOf(0, 1, 5, 6, 7775).map(EffWordlist::diceFor))
            .containsExactly("11111", "11112", "11116", "11121", "66666")
    }

    @Test
    fun `a truncated or reordered list is refused`() {
        val lines = (0 until 7776).map { "${EffWordlist.diceFor(it)}\tw${"abcdefghij"[it % 10]}${it.toString().map { d -> 'a' + (d - '0') }.joinToString("")}" }

        assertThat(runCatching { EffWordlist.parse(lines.dropLast(1)) }).isFailure()
        assertThat(runCatching { EffWordlist.parse(lines.reversed()) }).isFailure()
        assertThat(EffWordlist.parse(lines)).hasSize(7776)
    }

    private fun pow(base: Int, exponent: Int): Int = (0 until exponent).fold(1) { acc, _ -> acc * base }
}
