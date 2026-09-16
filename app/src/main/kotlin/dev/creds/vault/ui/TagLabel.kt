package dev.creds.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.creds.vault.core.model.Tag
import dev.creds.vault.core.ui.theme.CredsRadiusSmall

/**
 * The colours a tag can take.
 *
 * A fixed set rather than a free picker: each has been chosen to hold contrast as a small
 * dot on both the light and dark surfaces, which an arbitrary colour would not.
 */
object TagPalette {
    val colors: List<Int> = listOf(
        0xFF1A6B63, // teal
        0xFF3B6FB6, // blue
        0xFF6B5B95, // muted violet
        0xFF9A4F6E, // rose
        0xFFB3261E, // red
        0xFFC2600B, // orange
        0xFFB08600, // amber
        0xFF2E7D5B, // green
    ).map { it.toInt() }
}

/** A tag's colour, or the theme's secondary colour when it has none. */
@Composable
fun tagColor(color: Int?): Color =
    color?.let(::Color) ?: MaterialTheme.colorScheme.secondary

@Composable
fun TagDot(color: Int?, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier
            .size(size)
            .background(tagColor(color), CircleShape),
    )
}

/** Compact, non-interactive tag marker — text + dot, no chip chrome. */
@Composable
fun TagLabel(tag: Tag, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TagDot(tag.color, size = 5.dp)
        Text(
            tag.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A selectable colour swatch. Null [color] is the "no colour" option. */
@Composable
fun ColorSwatch(
    color: Int?,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CredsRadiusSmall)
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .size(32.dp)
            .border(1.5.dp, ring, shape)
            .padding(5.dp)
            .background(tagColor(color), shape),
    )
}
