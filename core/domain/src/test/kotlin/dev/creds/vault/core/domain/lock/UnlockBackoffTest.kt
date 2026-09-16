package dev.creds.vault.core.domain.lock

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class UnlockBackoffTest {

    @Test
    fun `no penalty before any failure`() {
        assertThat(UnlockBackoff.delayMillis(0)).isEqualTo(0)
        assertThat(UnlockBackoff.delayMillis(-1)).isEqualTo(0)
    }

    @Test
    fun `doubles from one second`() {
        assertThat(UnlockBackoff.delayMillis(1)).isEqualTo(1_000)
        assertThat(UnlockBackoff.delayMillis(2)).isEqualTo(2_000)
        assertThat(UnlockBackoff.delayMillis(3)).isEqualTo(4_000)
        assertThat(UnlockBackoff.delayMillis(4)).isEqualTo(8_000)
        assertThat(UnlockBackoff.delayMillis(9)).isEqualTo(256_000)
    }

    @Test
    fun `caps at five minutes`() {
        // The doubling passes the cap between the ninth and tenth failure.
        assertThat(UnlockBackoff.delayMillis(10)).isEqualTo(300_000)
        assertThat(UnlockBackoff.delayMillis(50)).isEqualTo(300_000)
    }

    @Test
    fun `a huge failure count cannot overflow into a short delay`() {
        // The bug this guards: shifting past 63 bits wraps and could yield a negative or
        // tiny delay, turning the lockout off exactly when it matters most.
        for (failures in listOf(62, 63, 64, 100, 1_000, Int.MAX_VALUE)) {
            assertThat(UnlockBackoff.delayMillis(failures)).isEqualTo(300_000)
        }
    }

    @Test
    fun `remaining counts down`() {
        assertThat(UnlockBackoff.remainingMillis(3, lastFailureAt = 0, now = 0)).isEqualTo(4_000)
        assertThat(UnlockBackoff.remainingMillis(3, lastFailureAt = 0, now = 1_000)).isEqualTo(3_000)
        assertThat(UnlockBackoff.remainingMillis(3, lastFailureAt = 0, now = 4_000)).isEqualTo(0)
        assertThat(UnlockBackoff.remainingMillis(3, lastFailureAt = 0, now = 9_999)).isEqualTo(0)
    }

    @Test
    fun `no failures means no wait`() {
        assertThat(UnlockBackoff.remainingMillis(0, lastFailureAt = 0, now = 0)).isEqualTo(0)
    }

    @Test
    fun `a backwards clock does not extend the lockout`() {
        // Winding the device clock back must not let a lockout run forever against the
        // legitimate owner.
        assertThat(UnlockBackoff.remainingMillis(5, lastFailureAt = 10_000, now = 0)).isEqualTo(0)
    }

    @Test
    fun `allowed only once the delay has elapsed`() {
        assertThat(UnlockBackoff.isUnlockAllowed(0, 0, 0)).isTrue()
        assertThat(UnlockBackoff.isUnlockAllowed(1, 0, 500)).isFalse()
        assertThat(UnlockBackoff.isUnlockAllowed(1, 0, 1_000)).isTrue()
    }
}
