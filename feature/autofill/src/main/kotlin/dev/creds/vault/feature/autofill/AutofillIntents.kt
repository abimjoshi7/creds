package dev.creds.vault.feature.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import dev.creds.vault.core.domain.autofill.CapturedLogin
import dev.creds.vault.core.domain.autofill.FillOrigin
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the service reaches the app's autofill activity.
 *
 * The activity lives in `:app`, because unlocking needs the app's unlock screen, so it is
 * addressed by class name rather than by type. It is not exported: nothing outside Creds
 * can start it, so the extras below can only have come from this service.
 */
object AutofillIntents {

    const val ACTIVITY_CLASS = "dev.creds.vault.autofill.AutofillActivity"

    const val EXTRA_MODE = "dev.creds.vault.autofill.MODE"
    const val MODE_UNLOCK = "unlock"
    const val MODE_PICK = "pick"
    const val MODE_SAVE = "save"

    const val EXTRA_USERNAME = "dev.creds.vault.autofill.USERNAME"
    const val EXTRA_PASSWORD = "dev.creds.vault.autofill.PASSWORD"
    const val EXTRA_PACKAGE = "dev.creds.vault.autofill.PACKAGE"
    const val EXTRA_APP_LABEL = "dev.creds.vault.autofill.APP_LABEL"
    const val EXTRA_CERTS = "dev.creds.vault.autofill.CERTS"
    const val EXTRA_PAST_CERTS = "dev.creds.vault.autofill.PAST_CERTS"
    const val EXTRA_WEB_HOST = "dev.creds.vault.autofill.WEB_HOST"
    const val EXTRA_WEB_SITE = "dev.creds.vault.autofill.WEB_SITE"

    // Every pending intent gets its own request code: with FLAG_CANCEL_CURRENT, reusing
    // one would invalidate the sender already handed out for another dataset.
    private val requestCodes = AtomicInteger(0)

    /**
     * For authentication: mutable, because the framework adds the screen's structure to the
     * intent before starting the activity. The component is fixed, so nothing else about
     * where it goes can change.
     */
    fun fill(context: Context, mode: String): IntentSender {
        val intent = Intent().setClassName(context.packageName, ACTIVITY_CLASS).putExtra(EXTRA_MODE, mode)
        return PendingIntent.getActivity(
            context,
            requestCodes.incrementAndGet(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
    }

    /** For saving: immutable, since everything the activity needs is already in it. */
    fun save(context: Context, analysis: AutofillAnalysis, captured: CapturedLogin): IntentSender {
        val intent = Intent().setClassName(context.packageName, ACTIVITY_CLASS)
            .putExtra(EXTRA_MODE, MODE_SAVE)
            .putExtra(EXTRA_USERNAME, captured.username)
            .putExtra(EXTRA_PASSWORD, captured.password)
            .putExtra(EXTRA_PACKAGE, analysis.packageName)
            .putExtra(EXTRA_APP_LABEL, analysis.appLabel)
        when (val origin = analysis.origin) {
            is FillOrigin.App -> intent
                .putExtra(EXTRA_CERTS, origin.signingCerts.toTypedArray())
                .putExtra(EXTRA_PAST_CERTS, origin.pastSigningCerts.toTypedArray())
            is FillOrigin.Web -> intent
                .putExtra(EXTRA_WEB_HOST, origin.host)
                .putExtra(EXTRA_WEB_SITE, origin.site)
        }
        return PendingIntent.getActivity(
            context,
            requestCodes.incrementAndGet(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
    }

    /** Rebuilds the origin a save intent was made for. */
    fun originOf(intent: Intent): FillOrigin? {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return null
        val host = intent.getStringExtra(EXTRA_WEB_HOST)
        val site = intent.getStringExtra(EXTRA_WEB_SITE)
        return if (host != null && site != null) {
            FillOrigin.Web(packageName, host, site)
        } else {
            val certs = intent.getStringArrayExtra(EXTRA_CERTS)?.toSet().orEmpty()
            if (certs.isEmpty()) return null
            FillOrigin.App(packageName, certs, intent.getStringArrayExtra(EXTRA_PAST_CERTS)?.toSet().orEmpty())
        }
    }
}
