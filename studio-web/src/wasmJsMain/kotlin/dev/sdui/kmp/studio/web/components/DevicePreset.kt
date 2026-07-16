package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.theme.StudioIcons

/**
 * A device form factor the Studio can frame a screen preview inside — shared by the JSON-tab
 * live preview ([DevicePreviewFrame]) and the visual editor's WYSIWYG canvas so both surfaces
 * offer the same Phone / Tablet / Desktop choices with identical widths and corner radii.
 *
 * @property label short human name shown in the [DevicePresetPicker].
 * @property icon hand-built glyph for the picker segment (no `materialIconsExtended` dep).
 * @property contentWidth fixed logical content width, or `null` to fill the available width
 *   (Desktop stretches to the pane like a responsive web layout).
 * @property cornerRadius the framed surface's corner radius — larger on Phone, small on Desktop.
 */
internal enum class DevicePreset(
    val label: String,
    val icon: ImageVector,
    val contentWidth: Dp?,
    val cornerRadius: Dp,
) {
    Phone("Phone", StudioIcons.DevicePhone, PHONE_WIDTH.dp, PHONE_RADIUS),
    Tablet("Tablet", StudioIcons.DeviceTablet, TABLET_WIDTH.dp, TABLET_RADIUS),
    Desktop("Desktop", StudioIcons.DeviceDesktop, null, DESKTOP_RADIUS),
    ;

    /** Muted caption for the current width, e.g. `"390dp"` or `"Fill"` for a stretching preset. */
    val widthLabel: String
        get() = contentWidth?.let { "${it.value.toInt()}dp" } ?: "Fill"
}

/**
 * Segmented Phone / Tablet / Desktop selector, optionally trailed by a muted width caption.
 *
 * @param showWidthLabel whether to render the selected preset's [DevicePreset.widthLabel] to the
 *   right of the segments (kept off where header space is tight).
 */
@Composable
internal fun DevicePresetPicker(
    selected: DevicePreset,
    onSelect: (DevicePreset) -> Unit,
    modifier: Modifier = Modifier,
    showWidthLabel: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PICKER_LABEL_GAP),
    ) {
        SegmentedControl(
            options = DevicePreset.entries.map { SegmentOption(label = it.label, icon = it.icon) },
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(DevicePreset.entries[it]) },
        )
        if (showWidthLabel) {
            Text(
                text = selected.widthLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PHONE_WIDTH = 390
private const val TABLET_WIDTH = 768
private val PHONE_RADIUS = 24.dp
private val TABLET_RADIUS = 16.dp
private val DESKTOP_RADIUS = 8.dp
private val PICKER_LABEL_GAP = 8.dp
