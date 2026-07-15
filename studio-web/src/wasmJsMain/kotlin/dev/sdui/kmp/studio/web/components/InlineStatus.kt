package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import dev.sdui.kmp.studio.web.theme.StudioIcons
import dev.sdui.kmp.studio.web.theme.studioColors

/** Semantic flavor of an [InlineStatus] line. */
internal enum class StatusKind { Neutral, Accent, Success, Error }

/**
 * One compact status line: a colored dot (or check/error icon for terminal states) plus a
 * small text. Replaces the plain colored `Text` status strips.
 */
@Composable
internal fun InlineStatus(kind: StatusKind, text: String, modifier: Modifier = Modifier) {
    val color = when (kind) {
        StatusKind.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusKind.Accent -> MaterialTheme.colorScheme.primary
        StatusKind.Success -> studioColors.success
        StatusKind.Error -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        when (kind) {
            StatusKind.Success -> StatusIcon(StudioIcons.CheckCircle, color)
            StatusKind.Error -> StatusIcon(StudioIcons.ErrorCircle, color)
            else -> Box(Modifier.size(STATUS_DOT_SIZE).background(color, CircleShape))
        }
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(STATUS_ICON_SIZE))
}

/**
 * Error-container banner listing server-side validation violations as a monospace bullet
 * list — used when a draft save is rejected by `Screen.serializer()` validation.
 */
@Composable
internal fun ValidationBanner(violations: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Validation failed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            violations.forEach { violation ->
                Text(
                    text = "• $violation",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

private val STATUS_DOT_SIZE = 6.dp
private val STATUS_ICON_SIZE = 14.dp
