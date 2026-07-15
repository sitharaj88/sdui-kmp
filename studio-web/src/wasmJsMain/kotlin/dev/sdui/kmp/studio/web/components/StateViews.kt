package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.theme.StudioIcons

/** Centered spinner + muted label, the app-wide loading state. */
@Composable
internal fun LoadingState(label: String = "Loading…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Centered error icon + message, with an optional Retry button. */
@Composable
internal fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = STATE_MAX_WIDTH),
        ) {
            Icon(
                imageVector = StudioIcons.ErrorCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(STATE_ICON_SIZE),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                OutlinedButton(onClick = onRetry) {
                    Icon(
                        imageVector = StudioIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(BUTTON_ICON_SIZE),
                    )
                    Text("  Retry")
                }
            }
        }
    }
}

/** Centered icon + title + optional hint for empty lists. */
@Composable
internal fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = StudioIcons.Inbox,
    hint: String? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = STATE_MAX_WIDTH),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = EMPTY_ICON_ALPHA),
                modifier = Modifier.size(EMPTY_ICON_SIZE),
            )
            Text(text = title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val SPINNER_SIZE = 28.dp
private val SPINNER_STROKE = 3.dp
private val STATE_MAX_WIDTH = 360.dp
private val STATE_ICON_SIZE = 24.dp
private val EMPTY_ICON_SIZE = 32.dp
private val BUTTON_ICON_SIZE = 14.dp
private const val EMPTY_ICON_ALPHA = 0.6f
