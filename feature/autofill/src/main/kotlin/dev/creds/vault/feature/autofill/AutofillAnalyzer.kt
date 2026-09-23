package dev.creds.vault.feature.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.autofill.AutofillId
import dev.creds.vault.core.domain.autofill.AutofillTrust
import dev.creds.vault.core.domain.autofill.FieldClassifier
import dev.creds.vault.core.domain.autofill.FieldRole
import dev.creds.vault.core.domain.autofill.FillOrigin
import dev.creds.vault.core.domain.autofill.FormKind
import dev.creds.vault.core.domain.autofill.ViewNodeInfo
import java.security.MessageDigest

/**
 * One screen to be filled or saved from, understood: who is asking, which inputs are
 * which, and (for saving) what was typed into them.
 */
data class AutofillAnalysis(
    val packageName: String,
    val appLabel: String,
    val origin: FillOrigin,
    val roles: Map<AutofillId, FieldRole>,
    val kinds: Set<FormKind>,
    val values: Map<AutofillId, String>,
) {
    val ids: List<AutofillId> get() = roles.keys.toList()

    /** How the origin is named to the user: the site, or the app's label. */
    val displayName: String
        get() = when (val o = origin) {
            is FillOrigin.Web -> o.site
            is FillOrigin.App -> appLabel
        }
}

/**
 * Turns an [AssistStructure] into an [AutofillAnalysis].
 *
 * The Android half of classification: this reads views and package signatures, and hands
 * the decisions to `FieldClassifier` and `AutofillTrust` in `:core:domain`.
 */
object AutofillAnalyzer {

    /**
     * Null when there is nothing to do: no fillable inputs, a request from Creds itself,
     * or an app whose signing certificate cannot be read — and an app that cannot be
     * identified is never filled.
     */
    fun analyze(context: Context, structure: AssistStructure): AutofillAnalysis? {
        val packageName = structure.activityComponent?.packageName ?: return null
        if (packageName == context.packageName) return null

        val nodes = mutableListOf<ViewNodeInfo<AutofillId>>()
        val values = LinkedHashMap<AutofillId, String>()
        val domains = HashMap<AutofillId, String>()
        var focusedDomain: String? = null
        var anyDomain: String? = null

        fun visit(node: AssistStructure.ViewNode, inheritedDomain: String?) {
            val domain = node.webDomain?.takeIf { it.isNotBlank() } ?: inheritedDomain
            if (domain != null && anyDomain == null) anyDomain = domain
            val id = node.autofillId
            if (id != null) {
                val html = node.htmlInfo
                val attributes = html?.attributes.orEmpty()
                    .mapNotNull { pair -> pair.first?.lowercase()?.let { key -> key to pair.second.orEmpty() } }
                    .toMap()
                val isText = node.autofillType == View.AUTOFILL_TYPE_TEXT &&
                    node.visibility == View.VISIBLE &&
                    node.isEnabled &&
                    !attributes["type"].equals("hidden", ignoreCase = true)
                nodes += ViewNodeInfo(
                    key = id,
                    isTextInput = isText,
                    autofillHints = node.autofillHints?.toList().orEmpty(),
                    inputType = node.inputType,
                    idEntry = node.idEntry,
                    hint = node.hint,
                    htmlTag = html?.tag,
                    htmlAttributes = attributes,
                    isFocused = node.isFocused,
                )
                node.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.let { values[id] = it }
                if (domain != null) domains[id] = domain
                if (node.isFocused && domain != null) focusedDomain = domain
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i), domain)
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode, null)

        val roles = FieldClassifier.classify(nodes)
        if (roles.isEmpty()) return null

        val signers = SigningCertificates.of(context.packageManager, packageName) ?: return null
        // The page the focused input sits on — an iframe's own domain, not its parent's.
        val webDomain = focusedDomain ?: roles.keys.firstNotNullOfOrNull(domains::get) ?: anyDomain
        val origin = AutofillTrust.origin(packageName, signers.current, signers.past, webDomain)

        return AutofillAnalysis(
            packageName = packageName,
            appLabel = appLabel(context.packageManager, packageName),
            origin = origin,
            roles = roles,
            kinds = FieldClassifier.kinds(roles.values),
            values = values.filterKeys { it in roles },
        )
    }

    private fun appLabel(packageManager: PackageManager, packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/**
 * The SHA-256 fingerprints an app is signed with.
 *
 * Current signers come from `apkContentsSigners`, which does not depend on how the
 * rotation history is ordered. Past keys are included only for a single-signer app with a
 * platform-verified rotation lineage.
 */
internal data class SigningCertificates(val current: Set<String>, val past: Set<String>) {

    companion object {
        fun of(packageManager: PackageManager, packageName: String): SigningCertificates? {
            val info = runCatching {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            }.getOrNull() ?: return null

            val current = info.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            if (current.isEmpty()) return null
            val past = if (!info.hasMultipleSigners() && info.hasPastSigningCertificates()) {
                info.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }.toSet() - current
            } else {
                emptySet()
            }
            return SigningCertificates(current, past)
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}
