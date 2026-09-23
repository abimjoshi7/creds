package dev.creds.vault.core.domain.autofill

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.creds.vault.core.model.AssociationKind
import dev.creds.vault.core.model.ItemAssociation
import dev.creds.vault.core.model.Template
import org.junit.jupiter.api.Test

class AutofillTrustTest {

    private val psl = PublicSuffixList.bundled
    private val chromeCert = "F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"
    private val appCert = "AA".repeat(32)
    private val otherCert = "BB".repeat(32)

    @Test
    fun `registrable domains follow the list, including private suffixes and wildcards`() {
        assertThat(psl.registrableDomain("login.bank.com")).isEqualTo("bank.com")
        assertThat(psl.registrableDomain("bank.com.evil.co")).isEqualTo("evil.co")
        assertThat(psl.registrableDomain("www.bbc.co.uk")).isEqualTo("bbc.co.uk")
        assertThat(psl.registrableDomain("alice.github.io")).isEqualTo("alice.github.io")
        assertThat(psl.registrableDomain("github.io")).isNull()
        assertThat(psl.registrableDomain("co.uk")).isNull()
        assertThat(psl.registrableDomain("localhost")).isNull()
        assertThat(psl.registrableDomain("192.168.1.1")).isNull()
        assertThat(psl.registrableDomain("LOGIN.Bank.COM.")).isEqualTo("bank.com")
    }

    @Test
    fun `wildcard and exception rules`() {
        val list = PublicSuffixList.parse(listOf("// comment", "com", "*.ck", "!www.ck"))

        assertThat(list.registrableDomain("a.b.ck")).isEqualTo("a.b.ck")
        assertThat(list.registrableDomain("b.ck")).isNull()
        assertThat(list.registrableDomain("www.ck")).isEqualTo("www.ck")
        assertThat(list.registrableDomain("x.www.ck")).isEqualTo("www.ck")
        // No rule matches: the implicit "*" makes the last label the suffix.
        assertThat(list.registrableDomain("a.example")).isEqualTo("a.example")
    }

    @Test
    fun `internationalised names compare in either form`() {
        assertThat(psl.siteKey("xn--bcher-kva.de")).isEqualTo(psl.siteKey("www.bücher.de"))
    }

    @Test
    fun `site keys fall back to the exact host`() {
        assertThat(psl.siteKey("192.168.1.1")).isEqualTo("192.168.1.1")
        assertThat(psl.siteKey("alice.github.io")).isEqualTo("alice.github.io")
        assertThat(psl.siteKey("github.io")).isEqualTo("github.io")
    }

    @Test
    fun `a web domain is believed only from a listed browser with its real key`() {
        val real = AutofillTrust.origin("com.android.chrome", setOf(chromeCert), emptySet(), "accounts.bank.com")
        val spoofed = AutofillTrust.origin("com.android.chrome", setOf(otherCert), emptySet(), "accounts.bank.com")
        val stranger = AutofillTrust.origin("com.example.app", setOf(appCert), emptySet(), "accounts.bank.com")

        assertThat(real).isEqualTo(FillOrigin.Web("com.android.chrome", "accounts.bank.com", "bank.com"))
        assertThat(spoofed).isInstanceOf(FillOrigin.App::class)
        assertThat(stranger).isInstanceOf(FillOrigin.App::class)
    }

    @Test
    fun `the bundled browser list parses and excludes the public test key`() {
        val browsers = TrustedBrowsers.bundled

        assertThat(browsers.isTrusted("com.android.chrome", setOf(chromeCert))).isTrue()
        assertThat(browsers.isTrusted("org.mozilla.firefox", setOf(otherCert))).isFalse()
        assertThat("com.android.browser" in browsers.packages).isFalse()
        assertThat(browsers.packages.none { it.contains("debug") }).isTrue()
        assertThat(runCatching { TrustedBrowsers.parse(listOf("pkg\tnot-a-cert")) }).isFailure()
    }

    @Test
    fun `a web page matches an item that names the site, at eTLD+1`() {
        val origin = FillOrigin.Web("com.android.chrome", "login.bank.com", "bank.com")

        assertThat(verdict(origin, websites = listOf("https://www.bank.com/signin"))).isEqualTo(TrustVerdict.TRUSTED)
        assertThat(verdict(origin, websites = listOf("bank.com"))).isEqualTo(TrustVerdict.TRUSTED)
        assertThat(verdict(origin, websites = listOf("https://bank.com.evil.co"))).isEqualTo(TrustVerdict.UNKNOWN)
        assertThat(verdict(origin, websites = listOf("https://notbank.com"))).isEqualTo(TrustVerdict.UNKNOWN)
    }

    @Test
    fun `shared hosting sites are not each other`() {
        val origin = AutofillTrust.origin("com.android.chrome", setOf(chromeCert), emptySet(), "mallory.github.io") as FillOrigin.Web

        assertThat(verdict(origin, websites = listOf("https://alice.github.io"))).isEqualTo(TrustVerdict.UNKNOWN)
    }

    @Test
    fun `a confirmed domain association trusts the site`() {
        val origin = FillOrigin.Web("com.android.chrome", "shop.example.com", "example.com")
        val association = ItemAssociation(AssociationKind.DOMAIN, "example.com", null, 1)

        assertThat(verdict(origin, associations = listOf(association))).isEqualTo(TrustVerdict.TRUSTED)
    }

    @Test
    fun `an app needs a confirmed package and key, and a different key is a conflict`() {
        val origin = FillOrigin.App("com.bank.app", setOf(appCert))
        val confirmed = ItemAssociation(AssociationKind.APP, "com.bank.app", appCert, 1)
        val otherKey = ItemAssociation(AssociationKind.APP, "com.bank.app", otherCert, 1)

        assertThat(verdict(origin)).isEqualTo(TrustVerdict.UNKNOWN)
        assertThat(verdict(origin, websites = listOf("https://bank.app"))).isEqualTo(TrustVerdict.UNKNOWN)
        assertThat(verdict(origin, associations = listOf(confirmed))).isEqualTo(TrustVerdict.TRUSTED)
        assertThat(verdict(origin, associations = listOf(otherKey))).isEqualTo(TrustVerdict.CONFLICT)
    }

    @Test
    fun `a rotated key keeps trust only through proven lineage`() {
        val rotated = FillOrigin.App("com.bank.app", setOf(otherCert), pastSigningCerts = setOf(appCert))
        val confirmedOld = ItemAssociation(AssociationKind.APP, "com.bank.app", appCert, 1)

        assertThat(verdict(rotated, associations = listOf(confirmedOld))).isEqualTo(TrustVerdict.TRUSTED)
    }

    @Test
    fun `recording trust stores what identifies the origin`() {
        val app = FillOrigin.App("com.bank.app", setOf(otherCert.lowercase(), appCert))

        assertThat(AutofillTrust.associationFor(app, now = 5))
            .isEqualTo(ItemAssociation(AssociationKind.APP, "com.bank.app", "$appCert,$otherCert", 5))
        assertThat(AutofillTrust.associationFor(FillOrigin.Web("b", "x.bank.com", "bank.com"), now = 5))
            .isEqualTo(ItemAssociation(AssociationKind.DOMAIN, "bank.com", null, 5))
    }

    @Test
    fun `website hosts tolerate a missing scheme and reject junk`() {
        assertThat(AutofillTrust.websiteHost("bank.com/login")).isEqualTo("bank.com")
        assertThat(AutofillTrust.websiteHost("  https://Bank.com:443/x ")).isEqualTo("bank.com")
        assertThat(AutofillTrust.websiteHost("")).isNull()
        assertThat(AutofillTrust.websiteHost("not a url at all")).isNull()
    }

    private fun verdict(
        origin: FillOrigin,
        websites: List<String> = emptyList(),
        associations: List<ItemAssociation> = emptyList(),
    ) = AutofillTrust.verdict(origin, AutofillCandidate("u", "t", "", Template.LOGIN, websites, associations), psl)
}
