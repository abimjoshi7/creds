package dev.creds.vault.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand: a desaturated teal that reads as "secure" without the banking-app navy cliché,
// on a warm neutral ground so long lists of credentials do not feel clinical.
private val Teal40 = Color(0xFF1F6F63)
private val Teal80 = Color(0xFF7FD1C1)
private val Sand99 = Color(0xFFFBF9F7)
private val Ink10 = Color(0xFF101418)

// Audit severity ramp. Kept out of the Material scheme so a theme change can never
// make "breached" and "strong" hard to tell apart.
val SeverityCritical = Color(0xFFB3261E)
val SeverityHigh = Color(0xFFC2600B)
val SeverityMedium = Color(0xFFB08600)
val SeverityOk = Color(0xFF2E7D5B)

internal val LightScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    secondary = Color(0xFF4A635E),
    background = Sand99,
    surface = Sand99,
    onBackground = Ink10,
    onSurface = Ink10,
    error = SeverityCritical,
)

internal val DarkScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFFB1CCC6),
    background = Ink10,
    surface = Color(0xFF171C21),
    onBackground = Color(0xFFE1E3E5),
    onSurface = Color(0xFFE1E3E5),
    error = Color(0xFFFFB4AB),
)
