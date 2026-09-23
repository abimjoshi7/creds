package dev.creds.vault.autofill

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.creds.vault.core.ui.components.QuietPanel

object AutofillSettingsTags {
    const val STATUS = "autofill-settings:status"
    const val ENABLE = "autofill-settings:enable"
}

enum class AutofillStatus { ENABLED, DISABLED, UNSUPPORTED }

@Composable
fun AutofillSettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(autofillStatus(context)) }
    // Coming back from system settings is how the status changes, so re-read on resume.
    LifecycleResumeEffect(Unit) {
        status = autofillStatus(context)
        onPauseOrDispose {}
    }
    AutofillSettingsScreen(
        status = status,
        onBack = onBack,
        onEnable = {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData("package:${context.packageName}".toUri())
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillSettingsScreen(
    status: AutofillStatus,
    onBack: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Autofill") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when (status) {
                    AutofillStatus.ENABLED -> "Creds is your autofill service."
                    AutofillStatus.DISABLED -> "Creds is not your autofill service."
                    AutofillStatus.UNSUPPORTED -> "This device does not support autofill services."
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(AutofillSettingsTags.STATUS),
            )
            if (status == AutofillStatus.DISABLED) {
                Button(
                    onClick = onEnable,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag(AutofillSettingsTags.ENABLE),
                ) { Text("Use Creds for autofill") }
            }
            QuietPanel {
                Text("How Creds decides what to fill", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Websites: only in browsers Creds can verify, and only items saved for that site. " +
                        "login.bank.com and bank.com are the same site; bank.com.evil.co is not.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Apps: the first time you fill an item in an app, Creds asks, then remembers the app and " +
                        "the key it is signed with. An app claiming the same name with a different key gets nothing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "When the vault is locked, suggestions ask you to unlock first. Nothing is filled without you choosing it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun autofillStatus(context: Context): AutofillStatus {
    val manager = context.getSystemService(AutofillManager::class.java) ?: return AutofillStatus.UNSUPPORTED
    return when {
        !manager.isAutofillSupported -> AutofillStatus.UNSUPPORTED
        manager.hasEnabledAutofillServices() -> AutofillStatus.ENABLED
        else -> AutofillStatus.DISABLED
    }
}
