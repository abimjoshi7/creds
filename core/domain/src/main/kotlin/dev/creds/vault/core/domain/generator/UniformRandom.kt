package dev.creds.vault.core.domain.generator

import java.security.SecureRandom

/**
 * Where random bytes come from.
 *
 * An interface so tests can script exact byte sequences and prove the rejection step
 * actually rejects. Production uses [SecureRandomSource].
 */
fun interface RandomSource {
    fun nextBytes(bytes: ByteArray)
}

/** The platform CSPRNG. On Android this is backed by the kernel's `/dev/urandom`. */
class SecureRandomSource(private val random: SecureRandom = SecureRandom()) : RandomSource {
    override fun nextBytes(bytes: ByteArray) = random.nextBytes(bytes)
}

/**
 * Unbiased integers in a range.
 *
 * `randomInt % bound` is the classic generator bug: unless `bound` divides 2³², the low
 * values come up slightly more often, and every character of every password inherits the
 * skew. This never reduces with a modulo. It draws 32 bits, masks them down to the
 * smallest power of two that covers the range, and throws the draw away if it lands
 * outside — so every accepted value is exactly as likely as every other, and at worst
 * half the draws are discarded.
 */
class UniformRandom(private val source: RandomSource) {

    private val buffer = ByteArray(Int.SIZE_BYTES)

    /** A value in `0 until bound`, each with probability exactly `1 / bound`. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, got $bound" }
        if (bound == 1) return 0

        val mask = -1 ushr Integer.numberOfLeadingZeros(bound - 1)
        while (true) {
            val candidate = nextRawInt() and mask
            if (candidate < bound) return candidate
        }
    }

    /** A uniformly chosen element. */
    fun <T> pick(from: List<T>): T = from[nextInt(from.size)]

    /** A uniformly chosen character. */
    fun pick(from: String): Char = from[nextInt(from.length)]

    @Synchronized
    private fun nextRawInt(): Int {
        source.nextBytes(buffer)
        val value = (buffer[0].toInt() and 0xFF shl 24) or
            (buffer[1].toInt() and 0xFF shl 16) or
            (buffer[2].toInt() and 0xFF shl 8) or
            (buffer[3].toInt() and 0xFF)
        buffer.fill(0)
        return value
    }
}
