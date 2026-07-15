package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One entry in a [SegmentedControl]. */
internal data class SegmentOption(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
)

/**
 * Compact 28dp segmented control inside a hairline-bordered container — the Studio's
 * replacement for mode-toggle button pairs (e.g. JSON | Visual).
 */
@Composable
internal fun SegmentedControl(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(SEGMENT_HEIGHT),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(SEGMENT_INSET)) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                val contentColor = when {
                    !option.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                    selected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            } else {
                                Color.Transparent
                            },
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .clickable(enabled = option.enabled && !selected) { onSelect(index) }
                        .padding(horizontal = SEGMENT_H_PADDING),
                ) {
                    if (option.icon != null) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(SEGMENT_ICON_SIZE),
                        )
                    }
                    Text(text = option.label, style = MaterialTheme.typography.labelLarge, color = contentColor)
                }
            }
        }
    }
}

private val SEGMENT_HEIGHT = 28.dp
private val SEGMENT_INSET = 2.dp
private val SEGMENT_H_PADDING = 10.dp
private val SEGMENT_ICON_SIZE = 13.dp
private const val DISABLED_ALPHA = 0.4f
