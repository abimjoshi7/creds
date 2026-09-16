package dev.creds.vault.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Cool stone + ink. Deliberately not Material tonal, not warm cream, not banking navy.
private val Ink = Color(0xFF0E1419)
private val Mist = Color(0xFFF3F5F7)
private val Paper = Color(0xFFFAFBFC)
private val Slate = Color(0xFF5A6570)
private val Accent = Color(0xFF1A6B63)
private val AccentSoft = Color(0xFF7EC8BE)
private val SurfaceRaised = Color(0xFFFFFFFF)
private val SurfaceDark = Color(0xFF141A20)
private val MistDark = Color(0xFF0C1014)

// Audit severity ramp. Kept out of the Material scheme so a theme change can never
// make "breached" and "strong" hard to tell apart.
val SeverityCritical = Color(0xFFB3261E)
val SeverityHigh = Color(0xFFC2600B)
val SeverityMedium = Color(0xFFB08600)
val SeverityOk = Color(0xFF2E7D5B)

internal val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EDE9),
    onPrimaryContainer = Color(0xFF0A3D38),
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8ECF0),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF4A5D73),
    background = Mist,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6EAEE),
    onSurfaceVariant = Slate,
    outline = Color(0xFFC5CCD4),
    outlineVariant = Color(0xFFDDE2E7),
    error = SeverityCritical,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Mist,
    surfaceContainer = Color(0xFFEEF1F4),
    surfaceContainerHigh = SurfaceRaised,
    surfaceContainerHighest = Color(0xFFE8ECF0),
)

internal val DarkScheme = darkColorScheme(
    primary = AccentSoft,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F4A44),
    onPrimaryContainer = AccentSoft,
    secondary = Color(0xFFA8B4BF),
    onSecondary = MistDark,
    secondaryContainer = Color(0xFF2A323A),
    onSecondaryContainer = Color(0xFFD5DCE3),
    tertiary = Color(0xFF9BB0C7),
    background = MistDark,
    onBackground = Color(0xFFE4E8EC),
    surface = SurfaceDark,
    onSurface = Color(0xFFE4E8EC),
    surfaceVariant = Color(0xFF232A32),
    onSurfaceVariant = Color(0xFFA8B4BF),
    outline = Color(0xFF3D4650),
    outlineVariant = Color(0xFF2A323A),
    error = Color(0xFFFFB4AB),
    surfaceContainerLowest = MistDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = Color(0xFF1A2128),
    surfaceContainerHigh = Color(0xFF232A32),
    surfaceContainerHighest = Color(0xFF2A323A),
)
