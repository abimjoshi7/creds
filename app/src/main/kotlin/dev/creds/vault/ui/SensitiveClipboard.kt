package dev.creds.vault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Copies [text] and marks it sensitive so keyboards leave it out of suggestions. */
fun Context.copySensitive(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
fun rememberCopySensitive(): (label: String, text: String) -> Unit {
    val context = LocalContext.current
    return { label, text -> context.copySensitive(label, text) }
}
