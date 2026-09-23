package dev.creds.vault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copies secrets to the clipboard and takes them back off it.
 *
 * Every copy is marked sensitive, so keyboards leave it out of suggestions and Android 13+
 * hides it from the paste preview, and is cleared [CLEAR_AFTER_MS] later.
 *
 * A process-wide object rather than something injected: the timer has to outlive the
 * screen that started it, and composables reach it without a Hilt entry point, which UI
 * tests running on a plain `Application` do not have.
 *
 * The clear is best effort. It runs in this process, so it is lost if the process dies
 * first, and Android 10+ only lets a focused app read the clipboard. When the clip can be
 * read it is cleared only if it is still ours — matched by the timestamp the system
 * stamped on it — so text the user copied since is left alone. When it cannot be read
 * it is cleared anyway: wiping something the user copied elsewhere is an annoyance,
 * leaving a password there is not a trade worth making.
 */
object SensitiveClipboard {

    const val CLEAR_AFTER_MS: Long = 30_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingClear: Job? = null

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                // ClipDescription.EXTRA_IS_SENSITIVE, spelled out: the constant is API 33, the
                // key is honoured by keyboards on older releases too.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        val stamp = clipboard.primaryClipDescription?.timestamp

        pendingClear?.cancel()
        pendingClear = scope.launch {
            delay(CLEAR_AFTER_MS)
            val current = clipboard.primaryClipDescription
            if (current == null || stamp == null || current.timestamp == stamp) {
                clipboard.clearPrimaryClip()
            }
        }
    }
}

/** A copy action bound to the current context. */
@Composable
fun rememberCopySensitive(): (label: String, text: String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { label, text -> SensitiveClipboard.copy(context, label, text) } }
}
