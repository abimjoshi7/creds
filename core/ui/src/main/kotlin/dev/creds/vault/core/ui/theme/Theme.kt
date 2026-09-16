package dev.creds.vault.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun CredsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic colour is opt-in per call rather than automatic: the audit severity ramp
    // has to stay legible, and a wallpaper-derived scheme can wash it out.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = CredsTypography,
        shapes = CredsShapes,
        content = content,
    )
}
