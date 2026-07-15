package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.theme.studioColors

/** Semantic flavor of a [StatusChip]. */
internal enum class ChipKind { Neutral, Accent, Success, Warning, Error }

/**
 * Dense 20dp status pill: tinted background, 1px tinted border, tiny label. The Studio's
 * replacement for `AssistChip` in list rows, toolbars, and panel headers.
 */
@Composable
internal fun StatusChip(text: String, kind: ChipKind = ChipKind.Neutral, modifier: Modifier = Modifier) {
    val color = kind.color()
    Surface(
        modifier = modifier.height(CHIP_HEIGHT),
        shape = RoundedCornerShape(CHIP_RADIUS),
        color = color.copy(alpha = CHIP_BG_ALPHA),
        border = BorderStroke(1.dp, color.copy(alpha = CHIP_BORDER_ALPHA)),
    ) {
        Box(Modifier.padding(horizontal = CHIP_H_PADDING), contentAlignment = Alignment.Center) {
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun ChipKind.color(): Color = when (this) {
    ChipKind.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    ChipKind.Accent -> MaterialTheme.colorScheme.primary
    ChipKind.Success -> studioColors.success
    ChipKind.Warning -> studioColors.warning
    ChipKind.Error -> MaterialTheme.colorScheme.error
}

/**
 * Maps a studio-server experiment status string to a chip flavor. Unknown statuses render
 * neutral rather than failing — statuses are additive on the wire like everything else.
 */
internal fun experimentStatusChipKind(status: String): ChipKind = when (status.lowercase()) {
    "active", "running" -> ChipKind.Success
    "paused" -> ChipKind.Warning
    "draft" -> ChipKind.Neutral
    "completed", "archived" -> ChipKind.Neutral
    else -> ChipKind.Neutral
}

private val CHIP_HEIGHT = 20.dp
private val CHIP_RADIUS = 10.dp
private val CHIP_H_PADDING = 8.dp
private const val CHIP_BG_ALPHA = 0.12f
private const val CHIP_BORDER_ALPHA = 0.4f
