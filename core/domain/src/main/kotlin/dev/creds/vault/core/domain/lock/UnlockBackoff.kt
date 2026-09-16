package dev.creds.vault.core.domain.lock

/**
 * Exponential backoff after failed unlock attempts.
 *
 * Argon2id already makes each guess expensive, but that cost is only paid by someone
 * attacking the sealed key offline. Somebody holding the unlocked *device* and typing
 * into the unlock screen pays nothing, so the UI has to impose the cost itself.
 *
 * 1s doubling to a 300s cap: the first few failures are barely noticeable to a person
 * who mistyped, while a hundred attempts becomes hours rather than minutes.
 *
 * Pure Kotlin with no clock of its own — the caller supplies `now`. That keeps it
 * portable and makes it testable without sleeping or mocking time.
 */
object UnlockBackoff {

    /** First penalty, applied after the first failure. */
    const val BASE_DELAY_MS: Long = 1_000

    /** Ceiling, from the spec's lock policy table. */
    const val MAX_DELAY_MS: Long = 300_000

    /**
     * How long to lock out after [failures] consecutive failures.
     *
     * Zero failures means no penalty. Doubling is computed by shifting rather than
     * `Math.pow` so that a large failure count cannot overflow into a negative delay —
     * the shift is clamped well before that point.
     */
    fun delayMillis(failures: Int): Long {
        if (failures <= 0) return 0

        // 2^(failures-1) * BASE, saturating at the cap. Shifts above 40 would overflow a
        // Long, and the cap is reached long before then, so clamping the exponent first
        // is both correct and cheaper than computing a huge number and capping it.
        val exponent = (failures - 1).coerceAtMost(40)
        val scaled = BASE_DELAY_MS shl exponent

        return if (scaled <= 0 || scaled > MAX_DELAY_MS) MAX_DELAY_MS else scaled
    }

    /**
     * Milliseconds remaining before another attempt is allowed.
     *
     * Returns 0 when the user may try again. A clock that moved backwards — a manual
     * time change, an NTP correction — yields 0 rather than a negative or absurdly large
     * wait, so fiddling with the device clock can never lengthen a lockout into a
     * denial of service against the owner.
     */
    fun remainingMillis(failures: Int, lastFailureAt: Long, now: Long): Long {
        val delay = delayMillis(failures)
        if (delay == 0L) return 0

        val elapsed = now - lastFailureAt
        if (elapsed < 0) return 0

        return (delay - elapsed).coerceAtLeast(0)
    }

    /** Whether an attempt is permitted right now. */
    fun isUnlockAllowed(failures: Int, lastFailureAt: Long, now: Long): Boolean =
        remainingMillis(failures, lastFailureAt, now) == 0L
}
