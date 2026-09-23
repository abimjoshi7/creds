package dev.creds.vault.feature.autofill

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import dagger.hilt.android.AndroidEntryPoint
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.autofill.SavePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Autofill Framework entry point.
 *
 * Runs in the app's process, so "locked" and "unlocked" mean exactly what they mean in the
 * app. Every failure answers with no suggestions: a password manager that errors into a
 * wrong fill is worse than one that stays quiet.
 */
@AndroidEntryPoint
class CredsAutofillService : AutofillService() {

    @Inject
    lateinit var vaultManager: VaultManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val job = scope.launch {
            val response = try {
                val structure = request.fillContexts.lastOrNull()?.structure
                val analysis = structure?.let { AutofillAnalyzer.analyze(this@CredsAutofillService, it) }
                val inline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) request.inlineSuggestionsRequest else null
                when {
                    analysis == null -> null
                    !vaultManager.isUnlocked -> AutofillResponses.locked(this@CredsAutofillService, analysis, inline)
                    else -> vaultManager.withUnlocked { repository, vaultKey ->
                        AutofillResponses.unlocked(this@CredsAutofillService, analysis, repository, vaultKey, inline, System.currentTimeMillis())
                    } ?: AutofillResponses.locked(this@CredsAutofillService, analysis, inline)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            callback.onSuccess(response)
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val analysis = structure?.let { runCatching { AutofillAnalyzer.analyze(this, it) }.getOrNull() }
        val captured = analysis?.let { SavePlanner.capture(it.roles, it.values) }
        if (analysis == null || captured == null) {
            callback.onSuccess()
            return
        }
        // The save screen unlocks if it needs to; the framework starts it for us.
        callback.onSuccess(AutofillIntents.save(this, analysis, captured))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
