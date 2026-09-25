package dev.creds.vault.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creds.vault.core.data.prefs.LockPolicy
import dev.creds.vault.lock.BiometricAuthenticator
import dev.creds.vault.ui.PasswordField
import dev.creds.vault.ui.StrengthMeter
import kotlinx.coroutines.launch

object SettingsTags {
    const val CHANGE_PASSWORD = "settings:password"
    const val BIOMETRIC = "settings:biometric"
    const val LOCK_ON_BACKGROUND = "settings:background"
    const val GRACE = "settings:grace"
    const val SCREEN_OFF = "settings:screenoff"
    const val IDLE = "settings:idle"
    const val SECURE = "settings:secure"
    const val WIPE = "settings:wipe"
    const val CURRENT = "settings:password:current"
    const val NEW = "settings:password:new"
    const val CONFIRM = "settings:password:confirm"
    const val SAVE_PASSWORD = "settings:password:save"
    const val PASSWORD_ERROR = "settings:password:error"
    const val CONFIRM_WIPE = "settings:wipe:confirm"
    fun choice(value: Int) = "settings:choice:$value"
}

/** Everything the settings screen can ask for; defaults are no-ops for tests. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenAutofill: () -> Unit = {},
    val onBiometric: (Boolean) -> Unit = {},
    val onLockOnBackground: (Boolean) -> Unit = {},
    val onGrace: (Int) -> Unit = {},
    val onLockOnScreenOff: (Boolean) -> Unit = {},
    val onIdle: (Int) -> Unit = {},
    val onSecureFlag: (Boolean) -> Unit = {},
    val onWipe: (Int) -> Unit = {},
    val onOpenPasswordChange: () -> Unit = {},
    val onClosePasswordChange: () -> Unit = {},
    val onCurrentPassword: (String) -> Unit = {},
    val onNewPassword: (String) -> Unit = {},
    val onConfirmPassword: (String) -> Unit = {},
    val onSavePassword: () -> Unit = {},
)

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenAutofill: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val policy by viewModel.policy.collectAsStateWithLifecycle()
    val passwordChange by viewModel.passwordChange.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current as? FragmentActivity
    val biometricAvailable = remember(activity) { activity != null && BiometricAuthenticator.isAvailable(activity) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.messageShown()
    }

    SettingsScreen(
        policy = policy,
        biometricAvailable = biometricAvailable,
        passwordChange = passwordChange,
        snackbarHostState = snackbar,
        actions = SettingsActions(
            onBack = onBack,
            onOpenAutofill = onOpenAutofill,
            onBiometric = { enable ->
                if (!enable) {
                    viewModel.disableBiometric()
                } else {
                    val cipher = viewModel.createBiometricCipher()
                    if (activity == null || cipher == null) {
                        viewModel.biometricUnavailable()
                    } else {
                        scope.launch {
                            val authorised = BiometricAuthenticator(activity).authenticate(
                                cipher = cipher,
                                title = "Enable biometric unlock",
                                subtitle = "Confirm to seal a second copy of your vault key",
                            ) ?: return@launch
                            viewModel.enableBiometric(authorised)
                        }
                    }
                }
            },
            onLockOnBackground = viewModel::setLockOnBackground,
            onGrace = viewModel::setBackgroundGraceSeconds,
            onLockOnScreenOff = viewModel::setLockOnScreenOff,
            onIdle = viewModel::setIdleTimeoutMinutes,
            onSecureFlag = viewModel::setSecureFlag,
            onWipe = viewModel::setWipeAfterFailures,
            onOpenPasswordChange = viewModel::openPasswordChange,
            onClosePasswordChange = viewModel::closePasswordChange,
            onCurrentPassword = viewModel::onCurrentPasswordChange,
            onNewPassword = viewModel::onNewPasswordChange,
            onConfirmPassword = viewModel::onConfirmPasswordChange,
            onSavePassword = viewModel::changePassword,
        ),
        modifier = modifier,
    )
}

private val GRACE_CHOICES = listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes")
private val IDLE_CHOICES = listOf(1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour")
private val WIPE_CHOICES = listOf(0 to "Off", 5 to "After 5 failed attempts", 10 to "After 10 failed attempts", 20 to "After 20 failed attempts")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    policy: LockPolicy,
    biometricAvailable: Boolean,
    passwordChange: PasswordChangeState?,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var choosing by remember { mutableStateOf<Choice?>(null) }
    var confirmWipe by remember { mutableStateOf<Int?>(null) }
    var confirmScreenshots by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Heading("Security")
            ActionRow(
                title = "Change master password",
                summary = "Your data stays as it is; only the password that opens it changes",
                tag = SettingsTags.CHANGE_PASSWORD,
                onClick = actions.onOpenPasswordChange,
            )
            SwitchRow(
                title = "Unlock with biometrics",
                summary = if (biometricAvailable || policy.biometricUnlock) {
                    "Your master password keeps working too"
                } else {
                    "Set up a fingerprint or face unlock on this phone first"
                },
                checked = policy.biometricUnlock,
                enabled = biometricAvailable || policy.biometricUnlock,
                tag = SettingsTags.BIOMETRIC,
                onChange = actions.onBiometric,
            )
            ActionRow(
                title = "Autofill",
                summary = "Fill logins in other apps and browsers",
                onClick = actions.onOpenAutofill,
            )

            Heading("Auto-lock")
            SwitchRow(
                title = "Lock when you leave the app",
                summary = "Autofill needs a short grace period to hand you back",
                checked = policy.lockOnBackground,
                tag = SettingsTags.LOCK_ON_BACKGROUND,
                onChange = actions.onLockOnBackground,
            )
            if (policy.lockOnBackground) {
                ActionRow(
                    title = "Grace period",
                    summary = labelFor(GRACE_CHOICES, policy.backgroundGraceSeconds, "${policy.backgroundGraceSeconds} seconds"),
                    tag = SettingsTags.GRACE,
                    onClick = { choosing = Choice("Lock after leaving", GRACE_CHOICES, policy.backgroundGraceSeconds, actions.onGrace) },
                )
            }
            SwitchRow(
                title = "Lock when the screen turns off",
                checked = policy.lockOnScreenOff,
                tag = SettingsTags.SCREEN_OFF,
                onChange = actions.onLockOnScreenOff,
            )
            ActionRow(
                title = "Lock when idle",
                summary = "After " + labelFor(IDLE_CHOICES, policy.idleTimeoutMinutes, "${policy.idleTimeoutMinutes} minutes"),
                tag = SettingsTags.IDLE,
                onClick = { choosing = Choice("Lock when idle for", IDLE_CHOICES, policy.idleTimeoutMinutes, actions.onIdle) },
            )

            Heading("Privacy")
            SwitchRow(
                title = "Block screenshots",
                summary = "Also hides the vault in the recent-apps view",
                checked = policy.secureFlag,
                tag = SettingsTags.SECURE,
                onChange = { on -> if (on) actions.onSecureFlag(true) else confirmScreenshots = true },
            )

            Heading("Failed unlocks")
            ActionRow(
                title = "Erase the vault",
                summary = labelFor(WIPE_CHOICES, policy.wipeAfterFailures, "After ${policy.wipeAfterFailures} failed attempts"),
                tag = SettingsTags.WIPE,
                onClick = {
                    choosing = Choice("Erase the vault", WIPE_CHOICES, policy.wipeAfterFailures) { value ->
                        // Turning it off is always safe; turning it on is asked about first.
                        if (value == 0) actions.onWipe(0) else confirmWipe = value
                    }
                },
            )

            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Vaultesque $version · Your vault never leaves this phone unless you export it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
    }

    choosing?.let { choice ->
        ChoiceDialog(
            choice = choice,
            onChoose = {
                choosing = null
                choice.onChoose(it)
            },
            onDismiss = { choosing = null },
        )
    }

    confirmWipe?.let { attempts ->
        AlertDialog(
            onDismissRequest = { confirmWipe = null },
            shape = MaterialTheme.shapes.large,
            title = { Text("Erase after $attempts failed attempts?") },
            text = {
                Text(
                    "After $attempts wrong master passwords in a row, everything in this vault is deleted " +
                        "from this phone for good. There is no undo. Only turn this on if you keep an exported backup.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmWipe = null
                        actions.onWipe(attempts)
                    },
                    modifier = Modifier.testTag(SettingsTags.CONFIRM_WIPE),
                ) { Text("Turn on", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = null }) { Text("Cancel") } },
        )
    }

    if (confirmScreenshots) {
        AlertDialog(
            onDismissRequest = { confirmScreenshots = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Allow screenshots?") },
            text = {
                Text(
                    "Screenshots of the vault — including any password you reveal — can then be taken, " +
                        "synced to cloud photo backups, and shown in the recent-apps view.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmScreenshots = false
                    actions.onSecureFlag(false)
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { confirmScreenshots = false }) { Text("Keep blocked") } },
        )
    }

    passwordChange?.let { state -> ChangePasswordDialog(state, actions) }
}

private data class Choice(
    val title: String,
    val options: List<Pair<Int, String>>,
    val selected: Int,
    val onChoose: (Int) -> Unit,
)

private fun labelFor(options: List<Pair<Int, String>>, value: Int, fallback: String): String =
    options.firstOrNull { it.first == value }?.second ?: fallback

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActionRow(title: String, summary: String? = null, tag: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier
            .clickable(onClick = onClick)
            .let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
    )
}

@Composable
private fun ChoiceDialog(choice: Choice, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(choice.title) },
        text = {
            Column {
                choice.options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == choice.selected, role = Role.RadioButton, onClick = { onChoose(value) })
                            .padding(vertical = 10.dp)
                            .testTag(SettingsTags.choice(value)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = value == choice.selected, onClick = null)
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangePasswordDialog(state: PasswordChangeState, actions: SettingsActions) {
    AlertDialog(
        onDismissRequest = actions.onClosePasswordChange,
        shape = MaterialTheme.shapes.large,
        title = { Text("Change master password") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordField(
                    value = state.current,
                    onValueChange = actions.onCurrentPassword,
                    label = "Current password",
                    fieldTag = SettingsTags.CURRENT,
                    revealTag = "${SettingsTags.CURRENT}:reveal",
                    imeAction = ImeAction.Next,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = state.new,
                    onValueChange = actions.onNewPassword,
                    label = "New password",
                    fieldTag = SettingsTags.NEW,
                    revealTag = "${SettingsTags.NEW}:reveal",
                    imeAction = ImeAction.Next,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.new.isNotEmpty()) {
                    StrengthMeter(
                        band = state.strength.band,
                        crackTime = state.strength.crackTimeDisplay,
                        warning = state.strength.warning,
                        suggestions = state.strength.suggestions,
                    )
                }
                PasswordField(
                    value = state.confirm,
                    onValueChange = actions.onConfirmPassword,
                    label = "Confirm new password",
                    fieldTag = SettingsTags.CONFIRM,
                    revealTag = "${SettingsTags.CONFIRM}:reveal",
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "There is no reset. If you forget the new password, only an exported backup can bring your items back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(SettingsTags.PASSWORD_ERROR))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onSavePassword,
                enabled = !state.busy,
                modifier = Modifier.testTag(SettingsTags.SAVE_PASSWORD),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(if (state.busy) "Changing…" else "Change")
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onClosePasswordChange, enabled = !state.busy) { Text("Cancel") }
        },
    )
}
