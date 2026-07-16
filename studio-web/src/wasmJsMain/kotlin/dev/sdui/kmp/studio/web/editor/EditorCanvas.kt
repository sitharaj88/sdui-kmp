package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Container
import dev.sdui.kmp.studio.web.components.DeviceFrameScaffold
import dev.sdui.kmp.studio.web.theme.StudioIcons
import kotlin.math.roundToInt

/**
 * The WYSIWYG canvas: a dark, softly-textured backdrop holding a centered device frame that
 * renders the workspace's current tree through the inert [CanvasNode] renderers, plus an
 * overlay layer for editor chrome that must not affect layout (floating node-action toolbar,
 * drop-indicator line, drag ghost).
 *
 * The frame is the shared [DeviceFrameScaffold] pinned to the stock **light** theme (never dark
 * — the canvas is always light so selection outlines read against the content), so a Phone here
 * looks identical to the JSON tab's Phone preview. Editor-chrome colors are captured from the
 * Studio theme *before* the scaffold nests the stock theme and provided via [LocalChromeColors];
 * because composition locals flow through the call tree (not the theme tree), the nodes composed
 * inside the nested stock theme still read them.
 *
 * Tapping the backdrop clears the selection; taps on nodes are consumed by the innermost
 * [NodeChrome] before they reach here.
 */
@Composable
internal fun EditorCanvas(
    workspace: EditorWorkspaceState,
    modifier: Modifier = Modifier,
) {
    val chrome = ChromeColors(
        selection = MaterialTheme.colorScheme.primary,
        hover = MaterialTheme.colorScheme.outline,
        dropTargetFill = MaterialTheme.colorScheme.primary.copy(alpha = DROP_FILL_ALPHA),
        placeholderText = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DOT_GRID_ALPHA)
    var canvasBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val canvasOrigin = canvasBounds.topLeft

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { canvasBounds = it.boundsInWindow() }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { workspace.selection = null })
            }
            .drawBehind {
                drawDotGrid(dotColor)
                drawDropIndicator(workspace, chrome.selection, canvasBounds, canvasOrigin)
            },
    ) {
        CompositionLocalProvider(LocalChromeColors provides chrome) {
            val preset = workspace.canvasWidth
            // The frame is vertically centered on the backdrop and bounded to the viewport height:
            // a short screen floats centered, a tall screen scrolls INSIDE the device via the
            // scaffold's own scroll. Critically we must NOT wrap the scaffold in another
            // verticalScroll — the scaffold already scrolls its content, and nesting scrolls would
            // measure the inner one with an infinite max-height constraint and crash at runtime.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = CANVAS_H_PADDING, vertical = CANVAS_V_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                val maxFrameHeight = maxHeight
                DeviceFrameScaffold(
                    preset = preset,
                    dark = false,
                    fillHeight = false,
                    contentPadding = DEVICE_CONTENT_PADDING,
                    modifier = Modifier
                        .heightIn(max = maxFrameHeight)
                        .drawBehind { drawArtboardShadow(preset.cornerRadius.toPx()) }
                        .shadow(FRAME_ELEVATION, RoundedCornerShape(preset.cornerRadius)),
                ) {
                    CanvasNode(
                        node = workspace.mutator.current,
                        path = emptyList(),
                        workspace = workspace,
                    )
                }
            }
            NodeActionToolbar(workspace = workspace, canvasOrigin = canvasOrigin, chrome = chrome)
            DragGhost(workspace = workspace, canvasOrigin = canvasOrigin, chrome = chrome)
        }
    }
}

/**
 * Soft grounding shadow lifting the artboard off the dark backdrop. A stack of translucent
 * rounded rects — larger and fainter outward, biased downward — fakes a blurred cast that a plain
 * black elevation shadow can't read against the dark canvas. Drawn behind (and outside) the frame,
 * so only the halo peeking past the opaque frame edges is visible.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArtboardShadow(cornerRadiusPx: Float) {
    var layer = 1
    while (layer <= ARTBOARD_SHADOW_LAYERS) {
        val t = layer / ARTBOARD_SHADOW_LAYERS.toFloat()
        val spread = ARTBOARD_SHADOW_SPREAD.toPx() * t
        val dy = ARTBOARD_SHADOW_Y_OFFSET.toPx() * t
        drawRoundRect(
            color = Color.Black.copy(alpha = ARTBOARD_SHADOW_ALPHA * (1f - t)),
            topLeft = Offset(-spread, -spread + dy),
            size = Size(size.width + spread * 2f, size.height + spread * 2f),
            cornerRadius = CornerRadius(cornerRadiusPx + spread),
        )
        layer++
    }
}

/** Subtle dotted grid grounding the backdrop, drawn behind the device frame. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDotGrid(color: Color) {
    val step = DOT_SPACING.toPx()
    if (step <= 0f) return
    val radius = DOT_RADIUS.toPx()
    var y = step
    while (y < size.height) {
        var x = step
        while (x < size.width) {
            drawCircle(color = color, radius = radius, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

/**
 * Drop indicator: a 2px accent gap line with end dots, drawn under the frame's overlay
 * coordinates (window → local via [canvasOrigin]). Skipped when the active location belongs to
 * the layers zone — that panel draws its own line.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDropIndicator(
    workspace: EditorWorkspaceState,
    color: Color,
    canvasBounds: androidx.compose.ui.geometry.Rect,
    canvasOrigin: Offset,
) {
    val location = workspace.dragState.active ?: return
    if (!canvasBounds.overlaps(location.indicatorBounds)) return
    val rect = location.indicatorBounds.translate(-canvasOrigin)
    drawLine(
        color = color,
        start = Offset(rect.left, rect.center.y),
        end = Offset(rect.right, rect.center.y),
        strokeWidth = DROP_LINE_STROKE_PX,
    )
    drawCircle(color = color, radius = DROP_LINE_DOT_RADIUS_PX, center = rect.centerLeft)
    drawCircle(color = color, radius = DROP_LINE_DOT_RADIUS_PX, center = rect.centerRight)
}

/**
 * Floating pill anchored just above the selected node, drawn from registry geometry. Merges the
 * type/id tag with quick actions — move up/down (reorder within the parent), duplicate, delete.
 * The root node has no toolbar: it cannot be moved, duplicated, or deleted.
 */
@Composable
private fun BoxScope.NodeActionToolbar(
    workspace: EditorWorkspaceState,
    canvasOrigin: Offset,
    chrome: ChromeColors,
) {
    val selected = workspace.selection ?: return
    if (selected.isEmpty() || workspace.dragState.isDragging) return
    val bounds = workspace.dropRegistry.boundsOf(selected) ?: return
    val node = workspace.mutator.current.childAt(selected) ?: return
    val parent = workspace.mutator.current.childAt(selected.dropLast(1)) as? Container
    val index = selected.last()
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val local = bounds.topLeft - canvasOrigin
    // The pill normally floats just above the node. If placing it there would collide with the
    // sibling directly above (tightly stacked content) or run off the top of the canvas, flip it
    // just below the node's top-left instead so it never occludes a neighbour.
    val nodeTopWindow = bounds.top
    val prevSiblingBottom = if (index > 0) {
        workspace.dropRegistry.boundsOf(selected.dropLast(1) + (index - 1))?.bottom
    } else {
        null
    }
    Surface(
        color = chrome.selection,
        shape = RoundedCornerShape(TOOLBAR_CORNER_RADIUS),
        shadowElevation = PILL_ELEVATION,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                val gap = TOOLBAR_ABOVE_GAP.toPx()
                val flipBelow = local.y < PILL_FLIP_THRESHOLD.toPx() ||
                    (prevSiblingBottom != null && prevSiblingBottom > nodeTopWindow - gap)
                val y = if (flipBelow) {
                    local.y + PILL_BELOW_INSET.toPx()
                } else {
                    (local.y - gap).coerceAtLeast(0f)
                }
                IntOffset(x = local.x.roundToInt(), y = y.roundToInt())
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp),
        ) {
            Text(
                text = "${node::class.simpleName}  ${node.id.value}",
                style = MaterialTheme.typography.labelSmall,
                color = onAccent,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp, end = 6.dp),
            )
            Box(
                Modifier
                    .width(TOOLBAR_DIVIDER_WIDTH)
                    .height(TOOLBAR_DIVIDER_HEIGHT)
                    .background(onAccent.copy(alpha = TOOLBAR_DIVIDER_ALPHA)),
            )
            ToolbarAction(
                icon = StudioIcons.MoveUp,
                description = "Move up",
                tint = onAccent,
                enabled = index > 0,
            ) { workspace.moveWithinParent(-1) }
            ToolbarAction(
                icon = StudioIcons.MoveDown,
                description = "Move down",
                tint = onAccent,
                enabled = parent != null && index < parent.children.size - 1,
            ) { workspace.moveWithinParent(1) }
            ToolbarAction(icon = StudioIcons.Copy, description = "Duplicate", tint = onAccent) {
                workspace.duplicateAt(selected)
            }
            ToolbarAction(icon = StudioIcons.Delete, description = "Delete", tint = onAccent) {
                workspace.deleteSelection()
            }
        }
    }
}

/** A compact icon button used inside the floating [NodeActionToolbar] pill. */
@Composable
private fun ToolbarAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TOOLBAR_ACTION_SIZE)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = DISABLED_ACTION_ALPHA),
            modifier = Modifier.size(TOOLBAR_ACTION_ICON_SIZE),
        )
    }
}

/**
 * Reorders the selected node within its own parent by [delta] positions (−1 up, +1 down),
 * clamped to the sibling range, and keeps the selection on the moved node. Root is immovable.
 */
internal fun EditorWorkspaceState.moveWithinParent(delta: Int) {
    val path = selection ?: return
    if (path.isEmpty()) return
    val parentPath = path.dropLast(1)
    val parent = mutator.current.childAt(parentPath) as? Container ?: return
    val index = path.last()
    val target = index + delta
    if (target < 0 || target >= parent.children.size) return
    val reordered = parent.children.toMutableList().apply { add(target, removeAt(index)) }
    val updated = mutator.current.replacingAt(parentPath, parent.withChildren(reordered)) ?: return
    commit(updated)
    selection = parentPath + target
}

/** Small floating card following the pointer while a drag is active. */
@Composable
private fun BoxScope.DragGhost(
    workspace: EditorWorkspaceState,
    canvasOrigin: Offset,
    chrome: ChromeColors,
) {
    val payload = workspace.dragState.payload ?: return
    val label = when (payload) {
        is DragPayload.NewNode -> payload.descriptor.typeName
        is DragPayload.ExistingNode ->
            workspace.mutator.current.childAt(payload.path)?.let { it::class.simpleName } ?: "node"
    }
    val local = workspace.dragState.pointerInWindow - canvasOrigin
    Surface(
        color = chrome.selection,
        shape = RoundedCornerShape(GHOST_CORNER_RADIUS),
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    x = (local.x + GHOST_POINTER_OFFSET_PX).roundToInt(),
                    y = (local.y + GHOST_POINTER_OFFSET_PX).roundToInt(),
                )
            }
            .alpha(GHOST_ALPHA),
    ) {
        Text(
            text = label ?: "node",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private const val DROP_FILL_ALPHA = 0.08f
private const val DROP_LINE_STROKE_PX = 2f
private const val DROP_LINE_DOT_RADIUS_PX = 3f
private const val DOT_GRID_ALPHA = 0.5f
private val DOT_SPACING = 24.dp
private val DOT_RADIUS = 1.dp
private val DEVICE_CONTENT_PADDING = 8.dp
private val CANVAS_H_PADDING = 28.dp
private val CANVAS_V_PADDING = 24.dp
private val FRAME_ELEVATION = 8.dp
private const val ARTBOARD_SHADOW_LAYERS = 6
private val ARTBOARD_SHADOW_SPREAD = 22.dp
private val ARTBOARD_SHADOW_Y_OFFSET = 12.dp
private const val ARTBOARD_SHADOW_ALPHA = 0.12f
private val GHOST_CORNER_RADIUS = 3.dp
private val TOOLBAR_CORNER_RADIUS = 5.dp
private val TOOLBAR_ACTION_SIZE = 24.dp
private val TOOLBAR_ACTION_ICON_SIZE = 14.dp
private val TOOLBAR_DIVIDER_WIDTH = 1.dp
private val TOOLBAR_DIVIDER_HEIGHT = 14.dp
private const val TOOLBAR_DIVIDER_ALPHA = 0.35f
private const val DISABLED_ACTION_ALPHA = 0.35f
private val TOOLBAR_ABOVE_GAP = 30.dp
private val PILL_FLIP_THRESHOLD = 40.dp
private val PILL_BELOW_INSET = 6.dp
private val PILL_ELEVATION = 4.dp
private const val GHOST_POINTER_OFFSET_PX = 12
private const val GHOST_ALPHA = 0.9f
