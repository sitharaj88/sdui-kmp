package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 32dp-dense button trio for toolbars. Material3's default 40dp buttons read as touch
 * targets; these pin height and content padding once so every toolbar in the Studio has
 * the same density without repeating `contentPadding` at each call site.
 */
@Composable
internal fun ToolbarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(TOOLBAR_BUTTON_HEIGHT),
        contentPadding = TOOLBAR_BUTTON_PADDING,
        shape = MaterialTheme.shapes.small,
    ) { ToolbarButtonContent(text, icon) }
}

/** Outlined variant of [ToolbarButton]. */
@Composable
internal fun ToolbarOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(TOOLBAR_BUTTON_HEIGHT),
        contentPadding = TOOLBAR_BUTTON_PADDING,
        shape = MaterialTheme.shapes.small,
    ) { ToolbarButtonContent(text, icon) }
}

/** Text variant of [ToolbarButton]. */
@Composable
internal fun ToolbarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(TOOLBAR_BUTTON_HEIGHT),
        contentPadding = TOOLBAR_BUTTON_PADDING,
        shape = MaterialTheme.shapes.small,
    ) { ToolbarButtonContent(text, icon) }
}

@Composable
private fun ToolbarButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(TOOLBAR_ICON_SIZE))
        Text("  ")
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

private val TOOLBAR_BUTTON_HEIGHT = 32.dp
private val TOOLBAR_BUTTON_PADDING = PaddingValues(horizontal = 12.dp)
private val TOOLBAR_ICON_SIZE = 14.dp
