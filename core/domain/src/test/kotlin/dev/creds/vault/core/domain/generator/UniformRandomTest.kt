package dev.creds.vault.core.domain.generator

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import org.junit.jupiter.api.Test
import java.util.Random

class UniformRandomTest {

    @Test
    fun `a draw outside the range is rejected, not reduced`() {
        // bound 5 masks to 3 bits. 7, 6 and 5 are in the mask but out of range; a modulo
        // would have turned them into 2, 1 and 0. They must be thrown away instead.
        val random = UniformRandom(scripted(7, 6, 5, 3))

        assertThat(random.nextInt(5)).isEqualTo(3)
    }

    @Test
    fun `high bits are masked off before the range check`() {
        val random = UniformRandom(scripted(0x7FFF_FFF2))

        assertThat(random.nextInt(4)).isEqualTo(2)
    }

    @Test
    fun `a bound of one needs no randomness`() {
        val random = UniformRandom { error("should not draw") }

        assertThat(random.nextInt(1)).isEqualTo(0)
    }

    @Test
    fun `a non-positive bound is refused`() {
        assertThat(runCatching { UniformRandom(SecureRandomSource()).nextInt(0) })
            .isFailure()
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `values stay in range for awkward bounds`() {
        val random = UniformRandom(SecureRandomSource())
        listOf(2, 3, 7, 10, 26, 62, 94, 7776, Int.MAX_VALUE).forEach { bound ->
            repeat(2_000) { assertThat(random.nextInt(bound)).isBetween(0, bound - 1) }
        }
    }

    @Test
    fun `draws are uniform`() {
        // Seeded, so this is a fixed computation rather than a test that fails one run in
        // a thousand. Chi-squared for 9 degrees of freedom at p = 0.001 is 27.88.
        val random = UniformRandom(seeded(20260916))
        val bound = 10
        val draws = 100_000
        val counts = IntArray(bound)
        repeat(draws) { counts[random.nextInt(bound)]++ }

        val expected = draws.toDouble() / bound
        val chiSquared = counts.sumOf { (it - expected) * (it - expected) / expected }
        assertThat(chiSquared).isLessThan(27.88)
    }

    private fun scripted(vararg values: Int): RandomSource {
        val queue = ArrayDeque(values.toList())
        return RandomSource { bytes ->
            val value = queue.removeFirst()
            bytes[0] = (value ushr 24).toByte()
            bytes[1] = (value ushr 16).toByte()
            bytes[2] = (value ushr 8).toByte()
            bytes[3] = value.toByte()
        }
    }

    private fun seeded(seed: Long): RandomSource {
        val random = Random(seed)
        return RandomSource(random::nextBytes)
    }
}
