package dev.creds.vault.core.domain.generator

/**
 * The EFF large wordlist: 7776 words, one per roll of five dice.
 *
 * Bundled verbatim, dice numbers and all, as
 * `eff_large_wordlist.txt` (SHA-256 `addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e`,
 * from eff.org/files/2016/07/18/eff_large_wordlist.txt), so the asset can be checked
 * against the published file byte for byte.
 *
 * Loaded from a JVM resource rather than an Android asset, which keeps this module free
 * of `android.*`; Android packages Java resources into the APK and serves them the same
 * way.
 */
object EffWordlist {

    const val SIZE: Int = 7776

    // Absolute, so R8 repackaging this class in a release build cannot break the lookup.
    private const val RESOURCE = "/dev/creds/vault/core/domain/generator/eff_large_wordlist.txt"

    /** Parsed on first use, on whatever thread asks first — it is about 100KB. */
    val words: List<String> by lazy {
        val stream = EffWordlist::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from the build")
        stream.bufferedReader(Charsets.UTF_8).use { parse(it.readLines()) }
    }

    /**
     * Parses and validates the list.
     *
     * Strict on purpose. A truncated or reordered file would still "work" — it would just
     * quietly produce weaker passphrases than the entropy readout claims, and nobody would
     * ever notice. So every dice index must be present, in order, and every word distinct.
     */
    fun parse(lines: List<String>): List<String> {
        val entries = lines.filter { it.isNotBlank() }
        require(entries.size == SIZE) { "Expected $SIZE words, found ${entries.size}" }

        val words = entries.mapIndexed { index, line ->
            val parts = line.split('\t')
            require(parts.size == 2) { "Line ${index + 1} is not `dice<TAB>word`" }
            require(parts[0] == diceFor(index)) {
                "Line ${index + 1}: expected dice ${diceFor(index)}, found ${parts[0]}"
            }
            require(parts[1].isNotEmpty() && parts[1].all { it in 'a'..'z' || it == '-' }) {
                "Line ${index + 1}: unexpected word `${parts[1]}`"
            }
            parts[1]
        }
        require(words.toSet().size == SIZE) { "The wordlist contains duplicates" }
        return words
    }

    /** The five dice faces for list position [index], e.g. 0 → `11111`, 7775 → `66666`. */
    internal fun diceFor(index: Int): String {
        val digits = CharArray(5)
        var rest = index
        for (position in 4 downTo 0) {
            digits[position] = '1' + rest % 6
            rest /= 6
        }
        return String(digits)
    }
}
