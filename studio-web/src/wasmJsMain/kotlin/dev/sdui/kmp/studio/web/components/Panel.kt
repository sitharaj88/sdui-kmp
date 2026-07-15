package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Studio's standard panel: a low surface with a crisp 1px hairline border and an
 * optional dense header row. Replaces bare Material3 `Card`s everywhere so every pane in
 * the app shares the same "pro tool" seam language.
 *
 * @param title optional header label; rendered UPPERCASE-styled (`labelMedium`) and muted.
 * @param headerActions optional trailing content in the header row (chips, icon buttons).
 * @param contentPadding inner padding around [content]; pass `0.dp` for flush lists.
 */
@Composable
internal fun StudioPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: androidx.compose.ui.unit.Dp = PANEL_CONTENT_PADDING,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            if (title != null || headerActions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(PANEL_HEADER_HEIGHT).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    headerActions?.invoke(this)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Column(Modifier.padding(contentPadding)) { content() }
        }
    }
}

private val PANEL_HEADER_HEIGHT = 34.dp
private val PANEL_CONTENT_PADDING = 12.dp
