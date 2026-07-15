package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The WYSIWYG canvas: a dark backdrop holding a centered "device" frame that renders the
 * workspace's current tree through the inert [CanvasNode] renderers, plus an overlay layer
 * for editor chrome that must not affect layout (selection label tag, drop indicator line,
 * drag ghost).
 *
 * The device frame nests a **stock light Material3 theme** so token-styled facsimiles look
 * like production clients (same rationale as the JSON tab's DevicePreviewFrame). Editor
 * chrome colors are captured from the Studio theme *before* nesting via [LocalChromeColors].
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
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { canvasOrigin = it.boundsInWindow().topLeft }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { workspace.selection = null })
            }
            .drawBehind {
                // Drop indicator: a 2px accent gap line, drawn under the frame's overlay
                // coordinates (window → local via canvasOrigin).
                val location = workspace.dragState.active ?: return@drawBehind
                val rect = location.indicatorBounds.translate(-canvasOrigin)
                drawLine(
                    color = chrome.selection,
                    start = Offset(rect.left, rect.center.y),
                    end = Offset(rect.right, rect.center.y),
                    strokeWidth = DROP_LINE_STROKE_PX,
                )
                drawCircle(color = chrome.selection, radius = DROP_LINE_DOT_RADIUS_PX, center = rect.centerLeft)
                drawCircle(color = chrome.selection, radius = DROP_LINE_DOT_RADIUS_PX, center = rect.centerRight)
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        CompositionLocalProvider(LocalChromeColors provides chrome) {
            DeviceFrame(workspace = workspace)
            SelectionLabel(workspace = workspace, canvasOrigin = canvasOrigin, chrome = chrome)
            DragGhost(workspace = workspace, canvasOrigin = canvasOrigin, chrome = chrome)
        }
    }
}

@Composable
private fun DeviceFrame(workspace: EditorWorkspaceState) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val widthModifier = workspace.canvasWidth.dp
        ?.let { Modifier.width(it.dp) }
        ?: Modifier.fillMaxWidth()
    MaterialTheme(
        colorScheme = lightColorScheme(),
        typography = Typography(),
        shapes = Shapes(),
    ) {
        Surface(
            shape = RoundedCornerShape(DEVICE_CORNER_RADIUS),
            border = BorderStroke(1.dp, hairline),
            color = MaterialTheme.colorScheme.background,
            modifier = widthModifier.padding(vertical = FRAME_V_MARGIN),
        ) {
            Box(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(DEVICE_CONTENT_PADDING),
            ) {
                CanvasNode(
                    node = workspace.mutator.current,
                    path = emptyList(),
                    workspace = workspace,
                )
            }
        }
    }
}

/** Floating type/id tag pinned above the selected node, drawn from registry geometry. */
@Composable
private fun BoxScope.SelectionLabel(
    workspace: EditorWorkspaceState,
    canvasOrigin: Offset,
    chrome: ChromeColors,
) {
    val selected = workspace.selection ?: return
    if (workspace.dragState.isDragging) return
    val bounds = workspace.dropRegistry.boundsOf(selected) ?: return
    val node = workspace.mutator.current.childAt(selected) ?: return
    val local = bounds.topLeft - canvasOrigin
    Surface(
        color = chrome.selection,
        shape = RoundedCornerShape(LABEL_CORNER_RADIUS),
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    x = local.x.roundToInt(),
                    y = (local.y - LABEL_HEIGHT_PX).roundToInt().coerceAtLeast(0),
                )
            },
    ) {
        Text(
            text = "${node::class.simpleName}  ${node.id.value}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
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
        shape = RoundedCornerShape(LABEL_CORNER_RADIUS),
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
private val DEVICE_CORNER_RADIUS = 12.dp
private val DEVICE_CONTENT_PADDING = 8.dp
private val FRAME_V_MARGIN = 16.dp
private val LABEL_CORNER_RADIUS = 3.dp
private const val LABEL_HEIGHT_PX = 20
private const val GHOST_POINTER_OFFSET_PX = 12
private const val GHOST_ALPHA = 0.9f
