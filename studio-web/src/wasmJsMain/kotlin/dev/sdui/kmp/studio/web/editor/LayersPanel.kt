package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Container
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.studio.web.theme.StudioIcons
import dev.sdui.kmp.protocol.Column as ColumnNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Layers panel: the tree as indented, expandable rows.
 *
 * Selection and hover state are fully shared with the canvas through the workspace — hovering
 * a row outlines the canvas node and vice versa. Hovered/selected rows expose duplicate and
 * delete icon affordances (duplicate deep-copies with fresh ids via [withFreshIds] so node-id
 * uniqueness survives the server's validation).
 *
 * Named slot children (AsyncImage placeholder, LazyList template, …) are shown as italic,
 * non-selectable rows — [TreePath] cannot address slots (see `CanvasNode`).
 */
@Composable
internal fun LayersPanel(
    workspace: EditorWorkspaceState,
    modifier: Modifier = Modifier,
) {
    var panelBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val indicatorColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .onGloballyPositioned { panelBounds = it.boundsInWindow() }
            .drawBehind {
                // Drop indicator for layers-zone drops; window → local via the panel origin.
                val location = workspace.dragState.active ?: return@drawBehind
                if (!panelBounds.overlaps(location.indicatorBounds)) return@drawBehind
                val rect = location.indicatorBounds.translate(-panelBounds.topLeft)
                drawLine(
                    color = indicatorColor,
                    start = androidx.compose.ui.geometry.Offset(rect.left, rect.center.y),
                    end = androidx.compose.ui.geometry.Offset(rect.right, rect.center.y),
                    strokeWidth = INDICATOR_STROKE_PX,
                )
            }
            .verticalScroll(rememberScrollState()),
    ) {
        LayerRow(node = workspace.mutator.current, path = emptyList(), depth = 0, workspace = workspace)
    }
}

@Composable
@Suppress("LongMethod")
private fun LayerRow(
    node: UiNode,
    path: TreePath,
    depth: Int,
    workspace: EditorWorkspaceState,
) {
    val key = path.joinToString("/")
    val expanded = workspace.expandedLayers[key] ?: true
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            workspace.hovered = path
        } else if (workspace.hovered == path) {
            workspace.hovered = null
        }
    }
    val isSelected = workspace.isSelected(path)
    val isHighlighted = isSelected || workspace.isHoverHighlighted(path)
    DisposableEffect(path) {
        onDispose { workspace.layersRegistry.unregister(path) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    isHighlighted -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
                MaterialTheme.shapes.extraSmall,
            )
            .onGloballyPositioned { coords ->
                workspace.layersRegistry.register(path, node is Container, coords.boundsInWindow())
            }
            .hoverable(interactions)
            .clickable { workspace.selection = path }
            .pointerInput(path) {
                if (path.isEmpty()) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val origin = workspace.layersRegistry.boundsOf(path)?.topLeft
                            ?: androidx.compose.ui.geometry.Offset.Zero
                        workspace.dragState.payload = DragPayload.ExistingNode(path)
                        workspace.dragState.pointerInWindow = origin + offset
                        workspace.selection = path
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        workspace.dragState.pointerInWindow += amount
                        workspace.updateDragTarget()
                    },
                    onDragEnd = { workspace.completeDrag() },
                    onDragCancel = { workspace.dragState.clear() },
                )
            }
            .padding(start = (depth * INDENT_DP).dp),
    ) {
        if (node is Container) {
            IconButton(
                onClick = { workspace.expandedLayers[key] = !expanded },
                modifier = Modifier.size(CHEVRON_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = if (expanded) StudioIcons.ChevronDown else StudioIcons.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(CHEVRON_ICON_SIZE),
                )
            }
        } else {
            Box(Modifier.size(CHEVRON_BUTTON_SIZE))
        }
        Icon(
            imageVector = descriptorFor(node)?.icon ?: StudioIcons.NodeUnknown,
            contentDescription = null,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(TYPE_ICON_SIZE),
        )
        Text(
            text = describeNode(node),
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        if (node is Container) {
            Text(
                text = node.children.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (isHighlighted && path.isNotEmpty()) {
            RowAction(icon = StudioIcons.Copy, description = "Duplicate") {
                workspace.duplicateAt(path)
            }
            RowAction(icon = StudioIcons.Delete, description = "Delete") {
                val updated = workspace.mutator.current.removingAt(path)
                if (updated != null) {
                    workspace.commit(updated)
                    if (workspace.selection == path) workspace.selection = null
                }
            }
        }
    }

    if (node is Container && expanded) {
        node.children.forEachIndexed { index, child ->
            LayerRow(node = child, path = path + index, depth = depth + 1, workspace = workspace)
        }
    }
    slotSummaries(node).takeIf { expanded }?.forEach { label ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .padding(start = ((depth + 1) * INDENT_DP + SLOT_EXTRA_INDENT).dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ACTION_BUTTON_SIZE)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ACTION_ICON_SIZE),
        )
    }
}

/** Italic slot rows shown under nodes with named single-slot children. */
private fun slotSummaries(node: UiNode): List<String> = when (node) {
    is AsyncImage -> buildList {
        if (node.placeholder != null) add("placeholder: ${node.placeholder!!::class.simpleName}")
        if (node.error != null) add("error: ${node.error!!::class.simpleName}")
    }
    is LazyList -> buildList {
        add("item template: ${node.itemTemplate::class.simpleName}")
        if (node.emptyState != null) add("empty state: ${node.emptyState!!::class.simpleName}")
        if (node.loadingState != null) add("loading state: ${node.loadingState!!::class.simpleName}")
        if (node.errorState != null) add("error state: ${node.errorState!!::class.simpleName}")
    }
    else -> emptyList()
}

/** Duplicates the node at [path] as its next sibling, re-identifying the whole subtree. */
internal fun EditorWorkspaceState.duplicateAt(path: TreePath) {
    if (path.isEmpty()) return
    val node = mutator.current.childAt(path) ?: return
    val parent = path.dropLast(1)
    val index = path.last() + 1
    val updated = mutator.current.insertingChildAt(parent, index, node.withFreshIds()) ?: return
    commit(updated)
    selection = parent + index
}

/**
 * Deep copy with brand-new node ids throughout the subtree — duplicating without re-ids would
 * trip the server's node-id uniqueness validation on save.
 */
internal fun UiNode.withFreshIds(): UiNode = when (this) {
    is ColumnNode -> copy(
        id = freshNodeId("column"),
        children = children.map { it.withFreshIds() },
    )
    is TextNode -> copy(id = freshNodeId("text"))
    is Button -> copy(id = freshNodeId("button"))
    is TextField -> copy(id = freshNodeId("field"))
    is Checkbox -> copy(id = freshNodeId("checkbox"))
    is Image -> copy(id = freshNodeId("image"))
    is AsyncImage -> copy(
        id = freshNodeId("async-image"),
        placeholder = placeholder?.withFreshIds(),
        error = error?.withFreshIds(),
    )
    is LazyList -> copy(
        id = freshNodeId("list"),
        itemTemplate = itemTemplate.withFreshIds(),
        emptyState = emptyState?.withFreshIds(),
        loadingState = loadingState?.withFreshIds(),
        errorState = errorState?.withFreshIds(),
    )
    is NavHost -> copy(id = freshNodeId("nav"))
    is NativeSurface -> copy(id = freshNodeId("native"))
    else -> this
}

private val ROW_HEIGHT = 26.dp
private const val INDICATOR_STROKE_PX = 2f
private const val INDENT_DP = 12
private const val SLOT_EXTRA_INDENT = 20
private val CHEVRON_BUTTON_SIZE = 20.dp
private val CHEVRON_ICON_SIZE = 14.dp
private val TYPE_ICON_SIZE = 14.dp
private val ACTION_BUTTON_SIZE = 22.dp
private val ACTION_ICON_SIZE = 13.dp
