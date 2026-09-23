package dev.creds.vault.feature.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.slice.Slice
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.domain.autofill.AutofillCandidate
import dev.creds.vault.core.domain.autofill.AutofillTrust
import dev.creds.vault.core.domain.autofill.FieldRole
import dev.creds.vault.core.domain.autofill.FillPlanner
import dev.creds.vault.core.domain.autofill.FormKind
import dev.creds.vault.core.domain.autofill.TrustVerdict
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultItem

/**
 * Builds what the autofill framework shows: suggestions, the unlock prompt, the search
 * entry, and the offer to save.
 *
 * Uses the pre-API-33 `Dataset` and `FillResponse` builders on every release. They are
 * deprecated, not removed, and one code path for API 28 through 36 is easier to get right
 * than two.
 */
@Suppress("DEPRECATION")
object AutofillResponses {

    private const val MAX_DATASETS = 20

    /** Locked: a single "Unlock Vaultesque" entry that authenticates the whole response. */
    fun locked(context: Context, analysis: AutofillAnalysis, inline: InlineSuggestionsRequest?): FillResponse {
        val title = context.getString(R.string.autofill_unlock)
        val builder = FillResponse.Builder()
        val sender = AutofillIntents.fill(context, AutofillIntents.MODE_UNLOCK)
        val ids = analysis.ids.toTypedArray()
        val inlinePresentation = inlinePresentation(context, inline, index = 0, title = title, subtitle = analysis.displayName, pinned = true)
        if (inlinePresentation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAuthentication(ids, sender, menu(context, title, analysis.displayName), inlinePresentation)
        } else {
            builder.setAuthentication(ids, sender, menu(context, title, analysis.displayName))
        }
        saveInfo(analysis)?.let(builder::setSaveInfo)
        return builder.build()
    }

    /**
     * Unlocked: a suggestion per item trusted for this app or site (and every card or
     * identity, for those forms), then "Search Vaultesque…" for everything else. Null when there
     * is nothing to show and nothing to save.
     */
    suspend fun unlocked(
        context: Context,
        analysis: AutofillAnalysis,
        repository: VaultRepository,
        vaultKey: VaultKey,
        inline: InlineSuggestionsRequest?,
        now: Long,
    ): FillResponse? {
        val candidates = repository.autofillCandidates()
        val offered = candidates
            .filter { isOffered(it, analysis) }
            .take(MAX_DATASETS)
            .mapNotNull { repository.load(vaultKey, it.uuid) }
            .filter { FillPlanner.offers(it, analysis.kinds) }

        val builder = FillResponse.Builder()
        var added = 0
        // One inline slot is kept for "Search Vaultesque…".
        val inlineSlots = if (inline != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inline.maxSuggestionCount - 1 else 0
        offered.forEachIndexed { index, item ->
            val inlineAllowed = index < inlineSlots
            dataset(context, analysis, item, now, inline.takeIf { inlineAllowed }, index)?.let {
                builder.addDataset(it)
                added++
            }
        }
        builder.addDataset(searchDataset(context, analysis, inline, index = added))
        saveInfo(analysis)?.let(builder::setSaveInfo)
        return builder.build()
    }

    /** The values of [item] for this screen as a dataset, or null if it has none to give. */
    fun dataset(
        context: Context,
        analysis: AutofillAnalysis,
        item: VaultItem,
        now: Long,
        inline: InlineSuggestionsRequest? = null,
        index: Int = 0,
    ): Dataset? {
        val values = FillPlanner.values(analysis.roles, item, now)
        if (values.isEmpty()) return null
        val subtitle = item.subtitle.ifEmpty { context.getString(R.string.autofill_item_subtitle) }
        val builder = Dataset.Builder(menu(context, item.title, subtitle))
        inlinePresentation(context, inline, index, item.title, subtitle, pinned = false)?.let { presentation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setInlinePresentation(presentation)
        }
        values.forEach { (id, value) -> builder.setValue(id, AutofillValue.forText(value)) }
        return builder.build()
    }

    private fun searchDataset(context: Context, analysis: AutofillAnalysis, inline: InlineSuggestionsRequest?, index: Int): Dataset {
        val title = context.getString(R.string.autofill_search)
        val builder = Dataset.Builder(menu(context, title, analysis.displayName))
        inlinePresentation(context, inline, index, title, analysis.displayName, pinned = true)?.let { presentation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setInlinePresentation(presentation)
        }
        // No values: choosing this entry opens the picker, which returns the real dataset.
        analysis.ids.forEach { id -> builder.setValue(id, null) }
        builder.setAuthentication(AutofillIntents.fill(context, AutofillIntents.MODE_PICK))
        return builder.build()
    }

    /**
     * Logins and one-time codes only for trusted items. Cards and identities are not tied
     * to a site, so every one is offered — they fill only when the user picks one.
     */
    private fun isOffered(candidate: AutofillCandidate, analysis: AutofillAnalysis): Boolean {
        val credentialForm = FormKind.LOGIN in analysis.kinds || FormKind.OTP in analysis.kinds
        if (credentialForm && AutofillTrust.verdict(analysis.origin, candidate) == TrustVerdict.TRUSTED) return true
        return (FormKind.CARD in analysis.kinds && candidate.template == Template.CARD) ||
            (FormKind.IDENTITY in analysis.kinds && candidate.template == Template.IDENTITY)
    }

    private fun saveInfo(analysis: AutofillAnalysis): SaveInfo? {
        val passwords = analysis.roles.filterValues { it == FieldRole.PASSWORD || it == FieldRole.NEW_PASSWORD }.keys
        if (passwords.isEmpty()) return null
        val usernames = analysis.roles.filterValues { it == FieldRole.USERNAME || it == FieldRole.EMAIL }.keys
        return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME, passwords.toTypedArray())
            .apply { if (usernames.isNotEmpty()) setOptionalIds(usernames.toTypedArray<AutofillId>()) }
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }

    private fun menu(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }

    @SuppressLint("NewApi") // Guarded by the SDK check on the first line.
    private fun inlinePresentation(
        context: Context,
        request: InlineSuggestionsRequest?,
        index: Int,
        title: String,
        subtitle: String,
        pinned: Boolean,
    ): InlinePresentation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || request == null) return null
        val specs = request.inlinePresentationSpecs
        if (specs.isEmpty() || index >= request.maxSuggestionCount) return null
        val spec = specs[minOf(index, specs.size - 1)]
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(spec.style)) return null
        return InlinePresentation(slice(context, title, subtitle), spec, pinned)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun slice(context: Context, title: String, subtitle: String): Slice {
        // Long-pressing a suggestion opens this; it has to be something harmless.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        val attribution = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setStartIcon(Icon.createWithResource(context, R.drawable.ic_autofill_key))
            .setContentDescription("$title, $subtitle")
            .build()
            .slice
    }
}
