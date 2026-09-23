package dev.creds.vault.core.domain.autofill

import dev.creds.vault.core.model.AssociationKind
import dev.creds.vault.core.model.ItemAssociation
import dev.creds.vault.core.model.Template
import java.net.URI

/**
 * Browsers allowed to tell autofill which website a form is on.
 *
 * Any app can put a `webDomain` on its views, so a web domain is only believed when the
 * app reporting it is a known browser *and* is signed by that browser's real key. See the
 * bundled `trusted_browsers.tsv` for where the list comes from.
 */
class TrustedBrowsers private constructor(private val certsByPackage: Map<String, Set<String>>) {

    val packages: Set<String> get() = certsByPackage.keys

    /** True only if [packageName] is listed and every one of its signers is a listed key. */
    fun isTrusted(packageName: String, signingCerts: Set<String>): Boolean {
        val allowed = certsByPackage[packageName] ?: return false
        return signingCerts.isNotEmpty() && signingCerts.all { normalizeCert(it) in allowed }
    }

    companion object {
        private const val RESOURCE = "/dev/creds/vault/core/domain/autofill/trusted_browsers.tsv"

        val bundled: TrustedBrowsers by lazy {
            val stream = TrustedBrowsers::class.java.getResourceAsStream(RESOURCE)
                ?: error("$RESOURCE is missing from the build")
            stream.bufferedReader(Charsets.UTF_8).use { parse(it.readLines()) }
        }

        fun parse(lines: List<String>): TrustedBrowsers {
            val map = HashMap<String, MutableSet<String>>()
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 2) { "Malformed browser entry: $line" }
                val cert = normalizeCert(parts[1])
                require(cert.length == 64 && cert.all { it in '0'..'9' || it in 'A'..'F' }) { "Malformed certificate: $line" }
                map.getOrPut(parts[0]) { HashSet() } += cert
            }
            return TrustedBrowsers(map)
        }
    }
}

/** Uppercase hex without separators, however a SHA-256 fingerprint was written. */
fun normalizeCert(fingerprint: String): String = fingerprint.replace(":", "").trim().uppercase()

/** Who is asking to be filled. */
sealed interface FillOrigin {

    /**
     * A native app, identified by package and signing key.
     *
     * @param signingCerts SHA-256 of every current signer.
     * @param pastSigningCerts SHA-256 of earlier keys the app has rotated away from, as
     *   proven to the platform by its signing lineage. Lets a key rotation keep trust.
     */
    data class App(
        val packageName: String,
        val signingCerts: Set<String>,
        val pastSigningCerts: Set<String> = emptySet(),
    ) : FillOrigin {
        /** How the signing identity is stored: all current signers, sorted, comma-joined. */
        val certKey: String get() = signingCerts.map(::normalizeCert).sorted().joinToString(",")
    }

    /** A page in a trusted browser. [site] is its eTLD+1, or the host itself if none. */
    data class Web(val browserPackage: String, val host: String, val site: String) : FillOrigin
}

enum class TrustVerdict {
    /** Confirmed before, or a web page on a site the item names. Suggest it. */
    TRUSTED,

    /** Never confirmed. Offer only through an explicit search, and ask before filling. */
    UNKNOWN,

    /** The app claims a package this item trusts but is signed by a different key. Never fill. */
    CONFLICT,
}

/** An item as autofill matching sees it: nothing secret, nothing decrypted. */
data class AutofillCandidate(
    val uuid: String,
    val title: String,
    val subtitle: String,
    val template: Template,
    val websites: List<String>,
    val associations: List<ItemAssociation>,
)

/**
 * The trust model of spec §7, as pure decisions.
 *
 * A mismatch always degrades to "no suggestion", never to a fill for the wrong app or
 * site. Nothing here trusts a package name on its own, a web domain from an app that is
 * not a verified browser, or a look-alike domain.
 */
object AutofillTrust {

    fun origin(
        packageName: String,
        signingCerts: Set<String>,
        pastSigningCerts: Set<String>,
        webDomain: String?,
        browsers: TrustedBrowsers = TrustedBrowsers.bundled,
        suffixes: PublicSuffixList = PublicSuffixList.bundled,
    ): FillOrigin {
        val host = webDomain?.let(PublicSuffixList::normalize)
        val site = host?.let(suffixes::siteKey)
        return if (host != null && site != null && browsers.isTrusted(packageName, signingCerts)) {
            FillOrigin.Web(browserPackage = packageName, host = host, site = site)
        } else {
            // An untrusted app reporting a web domain is treated as the app it is.
            FillOrigin.App(packageName, signingCerts.mapTo(HashSet(), ::normalizeCert), pastSigningCerts.mapTo(HashSet(), ::normalizeCert))
        }
    }

    fun verdict(
        origin: FillOrigin,
        candidate: AutofillCandidate,
        suffixes: PublicSuffixList = PublicSuffixList.bundled,
    ): TrustVerdict = when (origin) {
        is FillOrigin.App -> appVerdict(origin, candidate)
        is FillOrigin.Web -> webVerdict(origin, candidate, suffixes)
    }

    /** What to record when the user confirms a fill for [origin]. */
    fun associationFor(origin: FillOrigin, now: Long): ItemAssociation = when (origin) {
        is FillOrigin.App -> ItemAssociation(AssociationKind.APP, origin.packageName, origin.certKey, now)
        is FillOrigin.Web -> ItemAssociation(AssociationKind.DOMAIN, origin.site, certSha256 = null, confirmedAt = now)
    }

    /** The host of a saved website, tolerating a missing scheme. Null if unparseable. */
    fun websiteHost(website: String): String? {
        val trimmed = website.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching { URI(withScheme).host }.getOrNull()?.let(PublicSuffixList::normalize)
    }

    private fun appVerdict(origin: FillOrigin.App, candidate: AutofillCandidate): TrustVerdict {
        val forPackage = candidate.associations.filter {
            it.kind == AssociationKind.APP && it.value == origin.packageName
        }
        if (forPackage.isEmpty()) return TrustVerdict.UNKNOWN
        val current = origin.certKey
        val trusted = forPackage.any { association ->
            val stored = association.certSha256 ?: return@any false
            stored == current ||
                // A single-signer app that rotated keys: the platform verified the lineage,
                // so the key this item trusted is a genuine ancestor of today's.
                (origin.signingCerts.size == 1 && !stored.contains(',') && stored in origin.pastSigningCerts)
        }
        return if (trusted) TrustVerdict.TRUSTED else TrustVerdict.CONFLICT
    }

    private fun webVerdict(origin: FillOrigin.Web, candidate: AutofillCandidate, suffixes: PublicSuffixList): TrustVerdict {
        val confirmed = candidate.associations.any { it.kind == AssociationKind.DOMAIN && it.value == origin.site }
        val named = candidate.websites.any { website ->
            websiteHost(website)?.let(suffixes::siteKey) == origin.site
        }
        return if (confirmed || named) TrustVerdict.TRUSTED else TrustVerdict.UNKNOWN
    }
}
