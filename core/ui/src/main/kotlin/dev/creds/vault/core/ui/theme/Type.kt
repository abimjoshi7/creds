package dev.creds.vault.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val CredsTypography = Typography()

/**
 * Secrets are rendered in a monospace face with generous letter spacing.
 * Distinguishing `l` from `1` and `O` from `0` matters when someone is retyping a
 * password by hand, which is exactly when it is on screen.
 */
val SecretTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    letterSpacing = 0.6.sp,
)
