package dev.creds.vault.core.domain.autofill

import java.net.IDN

/**
 * The Public Suffix List: which parts of a host name are a registry anyone can register
 * under, and therefore where one site ends and another begins.
 *
 * Autofill matches sites at eTLD+1 — the registrable domain — so `login.bank.com` and
 * `bank.com` are the same site, `bank.com.evil.co` is `evil.co`, and two GitHub Pages
 * sites (`alice.github.io`, `mallory.github.io`) are different sites because
 * `github.io` is itself on the list.
 *
 * Bundled verbatim as `public_suffix_list.dat` from github.com/publicsuffix/list at commit
 * `3955e3ec29b94c3cca7bd4509c5f14a7c0959e26` (MPL-2.0; SHA-256
 * `a26f7d7e334778ed69216cedb5451ef82031feba6615c12039783cdd94e1fcae`), private domains
 * included — those are exactly the shared-hosting suffixes that matter here.
 */
class PublicSuffixList private constructor(
    private val exact: Set<String>,
    private val wildcards: Set<String>,
    private val exceptions: Set<String>,
) {

    /**
     * The registrable domain of [host], or null when there is none: an IP address, a
     * single label, or a host that is itself a public suffix.
     */
    fun registrableDomain(host: String): String? {
        val normalized = normalize(host) ?: return null
        if (isIpLiteral(normalized)) return null

        val labels = normalized.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null

        val suffixLength = publicSuffixLength(labels)
        if (labels.size <= suffixLength) return null
        return labels.takeLast(suffixLength + 1).joinToString(".")
    }

    /**
     * What two hosts must share to count as the same site: the registrable domain where
     * there is one, otherwise the exact normalised host (an IP address, `localhost`).
     */
    fun siteKey(host: String): String? {
        val normalized = normalize(host) ?: return null
        return registrableDomain(normalized) ?: normalized
    }

    /** Label count of the longest matching public suffix, defaulting to the last label. */
    private fun publicSuffixLength(labels: List<String>): Int {
        for (start in labels.indices) {
            val candidate = labels.subList(start, labels.size).joinToString(".")
            if (candidate in exceptions) return labels.size - start - 1
            if (candidate in exact) return labels.size - start
            val parent = labels.subList(start + 1, labels.size).joinToString(".")
            if (start + 1 < labels.size && parent in wildcards) return labels.size - start
        }
        return 1
    }

    companion object {

        private const val RESOURCE = "/dev/creds/vault/core/domain/autofill/public_suffix_list.dat"

        /** Parsed on first use, on whichever thread asks first — about 330KB. */
        val bundled: PublicSuffixList by lazy {
            val stream = PublicSuffixList::class.java.getResourceAsStream(RESOURCE)
                ?: error("$RESOURCE is missing from the build")
            stream.bufferedReader(Charsets.UTF_8).use { parse(it.readLines()) }
        }

        fun parse(lines: List<String>): PublicSuffixList {
            val exact = HashSet<String>()
            val wildcards = HashSet<String>()
            val exceptions = HashSet<String>()
            for (raw in lines) {
                // Rules end at the first whitespace; comments start with "//".
                val rule = raw.trim().substringBefore(' ').substringBefore('\t')
                if (rule.isEmpty() || rule.startsWith("//")) continue
                val lower = rule.lowercase()
                when {
                    lower.startsWith("!") -> exceptions += lower.drop(1)
                    lower.startsWith("*.") -> wildcards += lower.drop(2)
                    else -> exact += lower
                }
            }
            require(exact.isNotEmpty()) { "The public suffix list is empty" }
            return PublicSuffixList(exact, wildcards, exceptions)
        }

        /**
         * Lowercase Unicode, without a trailing dot, so `XN--BCHER-KVA.example.` and
         * `bücher.example` compare equal. Null when the name is not a valid IDN.
         */
        internal fun normalize(host: String): String? {
            val trimmed = host.trim().trimEnd('.').lowercase()
            if (trimmed.isEmpty()) return null
            if (isIpLiteral(trimmed)) return trimmed
            return runCatching { IDN.toUnicode(trimmed, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()
        }

        private fun isIpLiteral(host: String): Boolean =
            host.contains(':') || host.startsWith("[") ||
                host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }
    }
}
