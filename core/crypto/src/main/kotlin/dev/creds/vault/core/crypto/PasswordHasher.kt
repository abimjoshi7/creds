package dev.creds.vault.core.crypto

/**
 * Turns a master password into the 32-byte master key.
 *
 * An interface rather than a concrete class for the same reason
 * `PasswordStrengthEstimator` is one: the Android build backs it with argon2kt's native
 * library, and the callers — setup, unlock, recovery, export — should depend on the
 * contract, not the JNI.
 *
 * Implementations must not retain, log, or copy [password] into a [String].
 */
interface PasswordHasher {

    /**
     * Derives a master key from [password] and [salt].
     *
     * The returned array belongs to the caller, who must wipe it. [password] is *not*
     * wiped — the caller owns that buffer and may still need it (to seal a second copy
     * of the vault key, for instance).
     *
     * @param salt at least [KdfParams.MIN_SALT_BYTES]; store it alongside the sealed key.
     */
    fun deriveMasterKey(
        password: CharArray,
        salt: ByteArray,
        params: KdfParams = KdfParams.DEFAULT,
    ): ByteArray
}

/**
 * Argon2id cost parameters.
 *
 * The defaults are the spec's: m=64MiB, t=3, p=2. That is deliberately heavier than the
 * RFC 9106 low-memory profile — this runs once per unlock on a device the user is
 * holding, so a few hundred milliseconds is affordable, and every one of those
 * milliseconds is multiplied across an offline attacker's whole search space.
 *
 * Stored per vault rather than hardcoded at the call site: raising the defaults later
 * must not lock existing users out of their own data. The vault records the parameters
 * it was created with, and a rehash-on-unlock migration can lift them when convenient.
 */
data class KdfParams(
    /** Argon2 `m`, in kibibytes. */
    val memoryKib: Int,
    /** Argon2 `t`. */
    val iterations: Int,
    /** Argon2 `p`. */
    val parallelism: Int,
    /** Derived key length. */
    val outputBytes: Int = 32,
) {
    init {
        require(memoryKib >= 8 * parallelism) { "Argon2id requires m >= 8p" }
        require(iterations >= 1) { "Argon2id requires t >= 1" }
        require(parallelism >= 1) { "Argon2id requires p >= 1" }
        require(outputBytes >= 16) { "Derived key must be at least 128 bits" }
    }

    companion object {
        /** 128-bit salt, per the spec. */
        const val SALT_BYTES: Int = 16

        /** Below this a salt stops being a meaningful defence against precomputation. */
        const val MIN_SALT_BYTES: Int = 16

        /** m=64MiB, t=3, p=2 → 32-byte master key. */
        val DEFAULT = KdfParams(
            memoryKib = 64 * 1024,
            iterations = 3,
            parallelism = 2,
            outputBytes = 32,
        )
    }
}
