package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.studio.web.components.studioFieldColors
import dev.sdui.kmp.studio.web.theme.StudioIcons

/**
 * Side panel listing every widget kind an operator can spawn into the canvas, grouped by
 * [WidgetCategory] with a name filter.
 *
 * Each entry is a compact card; clicking calls [onAdd] with a freshly-built [UiNode] of that
 * type (the caller decides where it lands). Per ADR-0019 spawned nodes carry token-only
 * defaults — no hex colours or pixel sizes exist anywhere in the catalog.
 *
 * The optional [itemModifier] lets the workspace attach drag-source behaviour to every entry
 * without this panel knowing about the drag machinery.
 */
@Composable
internal fun WidgetPalette(
    onAdd: (UiNode) -> Unit,
    modifier: Modifier = Modifier,
    itemModifier: (WidgetDescriptor) -> Modifier = { Modifier },
) {
    var filter by remember { mutableStateOf("") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text("Filter widgets…", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = {
                Icon(
                    imageVector = StudioIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(FILTER_ICON_SIZE),
                )
            },
            singleLine = true,
            colors = studioFieldColors(),
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        val visible = DefaultWidgetPalette.filter {
            filter.isBlank() ||
                it.typeName.contains(filter.trim(), ignoreCase = true) ||
                it.description.contains(filter.trim(), ignoreCase = true)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(ENTRY_GAP),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            WidgetCategory.entries.forEach { category ->
                val entries = visible.filter { it.category == category }
                if (entries.isEmpty()) return@forEach
                Text(
                    text = category.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = SECTION_LETTER_SPACING,
                    modifier = Modifier.padding(top = SECTION_TOP_PADDING, bottom = SECTION_BOTTOM_PADDING),
                )
                entries.forEach { descriptor ->
                    PaletteEntry(
                        descriptor = descriptor,
                        onAdd = { onAdd(descriptor.factory()) },
                        modifier = itemModifier(descriptor),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteEntry(
    descriptor: WidgetDescriptor,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (hovered) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onAdd),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ENTRY_ICON_GAP),
            modifier = Modifier.padding(horizontal = ENTRY_H_PADDING, vertical = ENTRY_V_PADDING),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(ENTRY_ICON_BOX),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = descriptor.icon,
                        contentDescription = null,
                        tint = if (hovered) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(ENTRY_ICON_SIZE),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(ENTRY_TEXT_GAP)) {
                Text(
                    text = descriptor.typeName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = descriptor.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val FILTER_ICON_SIZE = 14.dp
private val ENTRY_ICON_SIZE = 16.dp
private val ENTRY_ICON_BOX = 30.dp
private val ENTRY_ICON_GAP = 10.dp
private val ENTRY_H_PADDING = 10.dp
private val ENTRY_V_PADDING = 9.dp
private val ENTRY_GAP = 6.dp
private val ENTRY_TEXT_GAP = 2.dp
private val SECTION_TOP_PADDING = 8.dp
private val SECTION_BOTTOM_PADDING = 3.dp
private val SECTION_LETTER_SPACING = 0.6.sp
