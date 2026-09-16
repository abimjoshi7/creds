package dev.creds.vault.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256, RFC 5869.
 *
 * Hand-rolled on [Mac] deliberately: `javax.crypto.KDF` only arrived in JDK 24 and is
 * absent from every Android release we target, so there is nothing on the platform to
 * delegate to. The construction is small enough to verify against the RFC's own test
 * vectors, which `HkdfTest` does.
 *
 * This is pure JVM — no `android.*` — so it is unit-testable on the host, unlike the
 * Argon2id step which needs the device's native library.
 */
object Hkdf {

    private const val ALGORITHM = "HmacSHA256"

    /** SHA-256 output size; HKDF calls this HashLen. */
    const val HASH_LENGTH: Int = 32

    /**
     * RFC 5869 §2.2. Compresses input keying material of any length and quality into a
     * uniform pseudorandom key.
     *
     * @param salt optional, non-secret. A null or empty salt becomes HashLen zero bytes,
     *   exactly as the RFC specifies.
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * RFC 5869 §2.3. Stretches a pseudorandom key into [length] bytes bound to [info].
     *
     * [info] is the domain separator: two different info strings over the same PRK give
     * independent keys, which is the entire basis of the key hierarchy in [VaultKey].
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LENGTH)) {
            "HKDF-SHA256 can emit 1..${255 * HASH_LENGTH} bytes, asked for $length"
        }

        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(prk, ALGORITHM))

        val output = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1

        while (filled < length) {
            // T(n) = HMAC(PRK, T(n-1) | info | n), with T(0) empty.
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()

            val take = minOf(block.size, length - filled)
            block.copyInto(output, filled, 0, take)
            filled += take
            counter++
        }

        block.wipe()
        return output
    }

    /**
     * Extract-then-expand in one call, wiping the intermediate PRK.
     *
     * Prefer this over calling [extract] and [expand] separately — the PRK is as
     * sensitive as the key it produces and is easy to forget about.
     */
    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return try {
            expand(prk, info, length)
        } finally {
            prk.wipe()
        }
    }
}
