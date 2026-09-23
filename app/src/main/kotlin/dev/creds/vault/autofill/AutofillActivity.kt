package dev.creds.vault.autofill

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.autofill.CapturedLogin
import dev.creds.vault.core.domain.autofill.FillOrigin
import dev.creds.vault.core.ui.theme.CredsTheme
import dev.creds.vault.feature.autofill.AutofillAnalyzer
import dev.creds.vault.feature.autofill.AutofillIntents
import dev.creds.vault.lock.BiometricAuthenticator
import dev.creds.vault.lock.LockCoordinator
import dev.creds.vault.unlock.UnlockRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Where autofill comes when it needs the user: to unlock, to choose an item, or to save.
 *
 * Not exported, and for filling it trusts nothing in its intent but the screen structure
 * the framework attached — it identifies the requesting app again itself. It always runs
 * with `FLAG_SECURE`, whatever the app's preference: it sits on top of someone else's app.
 */
@AndroidEntryPoint
class AutofillActivity : FragmentActivity() {

    @Inject
    lateinit var vaultManager: VaultManager

    @Inject
    lateinit var lockCoordinator: LockCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)

        lifecycleScope.launch {
            val request = withContext(Dispatchers.Default) { requestFrom(intent) }
            if (request == null) {
                finish()
                return@launch
            }
            setContent { CredsTheme { AutofillHost(request) } }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lockCoordinator.noteInteraction()
    }

    @Composable
    private fun AutofillHost(request: AutofillRequest, viewModel: AutofillViewModel = hiltViewModel()) {
        var initialised by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) { initialised = viewModel.isInitialised() }
        val repository by vaultManager.repository.collectAsStateWithLifecycle()
        val authenticator = remember { BiometricAuthenticator(this) }

        when {
            initialised == null -> Unit
            initialised == false -> NotSetUp()
            repository == null -> UnlockRoute(authenticator = authenticator, onUnlocked = {}, onWiped = ::finish)
            else -> when (request) {
                is AutofillRequest.Unlock -> LaunchedEffect(request) {
                    val response = viewModel.unlockedResponse(request.analysis)
                    finishWith(response)
                }
                is AutofillRequest.Pick -> PickerHost(request, viewModel)
                is AutofillRequest.Save -> SaveHost(request, viewModel)
            }
        }
    }

    @Composable
    private fun PickerHost(request: AutofillRequest.Pick, viewModel: AutofillViewModel) {
        LaunchedEffect(request) { viewModel.loadPicker(request.analysis) }
        val state by viewModel.picker.collectAsStateWithLifecycle()
        val analysis = request.analysis
        AutofillPickerScreen(
            state = state,
            appFingerprint = (analysis.origin as? FillOrigin.App)?.let { AutofillViewModel.fingerprint(it.certKey) },
            actions = AutofillPickerActions(
                onClose = ::finish,
                onQueryChange = viewModel::onQueryChange,
                onPick = { uuid -> viewModel.pick(analysis, uuid, confirmed = false, onDataset = ::finishWith) },
                onConfirm = { uuid ->
                    viewModel.dismissDialogs()
                    viewModel.pick(analysis, uuid, confirmed = true, onDataset = ::finishWith)
                },
                onDismiss = viewModel::dismissDialogs,
            ),
        )
    }

    @Composable
    private fun SaveHost(request: AutofillRequest.Save, viewModel: AutofillViewModel) {
        LaunchedEffect(request) { viewModel.loadSave(request) }
        val state by viewModel.save.collectAsStateWithLifecycle()
        AutofillSaveScreen(
            state = state,
            actions = AutofillSaveActions(
                onTitleChange = viewModel::onTitleChange,
                onSaveNew = { viewModel.saveLogin(request, updateUuid = null, onDone = ::finish) },
                onUpdate = { uuid -> viewModel.saveLogin(request, updateUuid = uuid, onDone = ::finish) },
                onNotNow = ::finish,
            ),
        )
    }

    @Composable
    private fun NotSetUp() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Vaultesque isn't set up yet", style = MaterialTheme.typography.titleMedium)
                Text("Open Vaultesque and create your vault first.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = ::finish) { Text("Close") }
            }
        }
    }

    private fun finishWith(result: Parcelable?) {
        if (result != null) {
            setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result))
        }
        finish()
    }

    private fun requestFrom(intent: Intent): AutofillRequest? = when (intent.getStringExtra(AutofillIntents.EXTRA_MODE)) {
        AutofillIntents.MODE_UNLOCK -> structure(intent)?.let { AutofillAnalyzer.analyze(this, it) }?.let(AutofillRequest::Unlock)
        AutofillIntents.MODE_PICK -> structure(intent)?.let { AutofillAnalyzer.analyze(this, it) }?.let(AutofillRequest::Pick)
        AutofillIntents.MODE_SAVE -> {
            val origin = AutofillIntents.originOf(intent)
            val password = intent.getStringExtra(AutofillIntents.EXTRA_PASSWORD)
            if (origin == null || password.isNullOrEmpty()) {
                null
            } else {
                val name = when (origin) {
                    is FillOrigin.Web -> origin.site
                    is FillOrigin.App -> intent.getStringExtra(AutofillIntents.EXTRA_APP_LABEL) ?: origin.packageName
                }
                AutofillRequest.Save(origin, name, CapturedLogin(intent.getStringExtra(AutofillIntents.EXTRA_USERNAME), password))
            }
        }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun structure(intent: Intent): AssistStructure? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }
}
