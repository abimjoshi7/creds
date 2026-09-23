package dev.creds.vault.core.domain.breach

import java.security.MessageDigest

/**
 * The offline breach check: passwords that appear in a public list of about a million
 * breached passwords.
 *
 * Bundled as a Bloom filter (see [BloomFilter]) at roughly a 0.1% false-positive rate,
 * built by `./gradlew :core:domain:buildBreachFilter` from SecLists'
 * `Passwords/Common-Credentials/xato-net-10-million-passwords-1000000.txt` at commit
 * `c205c36a445bff37f8e58a9ec829105cd4975c58` (MIT licensed; SHA-256
 * `424a3e03a17df0a2bc2b3ca749d81b04e79d59cb7aeec8876a5a3f308d0caf51`).
 *
 * Instant, needs no consent and sends nothing anywhere, which is why it is always on. A
 * rare false positive flags a strong password as breached; a false negative cannot
 * happen for anything in the list.
 */
object OfflineBreachList {

    // Absolute, so R8 repackaging this class cannot break the lookup.
    private const val RESOURCE = "/dev/creds/vault/core/domain/breach/breached_passwords.bloom"

    /** Loaded on first use — about 2MB — on whichever thread asks first. */
    private val filter: BloomFilter by lazy {
        val stream = OfflineBreachList::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from the build")
        stream.buffered().use(BloomFilter::readFrom)
    }

    val entries: Int get() = filter.entries

    fun contains(password: String): Boolean = password.isNotEmpty() && filter.mightContain(password)
}

/**
 * Where the online check gets a Have I Been Pwned range from.
 *
 * An interface so the network stays in `:core:data` and this module stays free of it.
 */
fun interface BreachRangeSource {
    /**
     * The range response for a five-character uppercase SHA-1 prefix: lines of
     * `SUFFIX:COUNT`. Throws on any network or HTTP failure.
     */
    suspend fun range(prefix: String): String
}

/**
 * Have I Been Pwned's k-anonymity range protocol.
 *
 * Only the first five hex characters of a password's SHA-1 are ever sent; the service
 * returns every suffix it knows under that prefix and the match happens here. Responses
 * are requested padded with fake zero-count entries, so their size says nothing about
 * how many real matches there were — and those padding lines are ignored.
 */
object HibpRange {

    const val PREFIX_LENGTH: Int = 5

    /** Uppercase hex SHA-1 of the UTF-8 bytes, as HIBP indexes them. */
    fun sha1Hex(password: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    fun prefix(sha1Hex: String): String = sha1Hex.take(PREFIX_LENGTH)

    /**
     * How many times the password with this full hash appears in the range [body].
     *
     * Zero when it does not appear, or appears only as padding.
     */
    fun count(body: String, sha1Hex: String): Int {
        val suffix = sha1Hex.drop(PREFIX_LENGTH)
        return body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(':').equals(suffix, ignoreCase = true) }
            ?.substringAfter(':', missingDelimiterValue = "0")
            ?.trim()
            ?.toIntOrNull()
            ?: 0
    }

    fun isValidPrefix(prefix: String): Boolean =
        prefix.length == PREFIX_LENGTH && prefix.all { it in '0'..'9' || it in 'A'..'F' }
}
