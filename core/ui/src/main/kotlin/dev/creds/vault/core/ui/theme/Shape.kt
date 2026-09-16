package dev.creds.vault.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Restrained radii — enough softness to feel finished, not the Material 28dp pill look.
 */
internal val CredsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

val CredsRadiusSmall = 8.dp
val CredsRadiusMedium = 12.dp
val CredsRadiusLarge = 16.dp
