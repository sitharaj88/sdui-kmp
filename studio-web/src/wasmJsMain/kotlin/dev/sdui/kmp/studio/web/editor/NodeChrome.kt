package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import dev.sdui.kmp.protocol.Container
import dev.sdui.kmp.protocol.UiNode

/**
 * Editor-chrome colors captured from the **Studio** theme before the canvas nests its stock
 * device theme — selection outlines must stay Studio-accent-blue even though the widget
 * facsimiles underneath render with production-like colors.
 */
@Immutable
internal class ChromeColors(
    val selection: Color,
    val hover: Color,
    val dropTargetFill: Color,
    val placeholderText: Color,
)

/** Provided by `EditorCanvas`; reading outside the canvas is a programming error. */
internal val LocalChromeColors = staticCompositionLocalOf<ChromeColors> {
    error("LocalChromeColors accessed outside EditorCanvas")
}

/**
 * Wraps every rendered canvas node with editor chrome:
 *
 *  * **Geometry registration** — the node's window rect goes into the workspace's
 *    [DropTargetRegistry] on every layout pass (and unregisters on dispose), powering drop
 *    resolution and the overlay's selection label.
 *  * **Tap-to-select** — `detectTapGestures` consumes the tap, so the innermost node under
 *    the pointer wins; clicking a child never also selects its parent.
 *  * **Hover** — updates the shared `workspace.hovered` so the layers panel highlights the
 *    same node; suppressed while a drag is active.
 *  * **Outlines** — drawn after content ([drawWithContent]) with zero layout impact: 2px
 *    accent for selection, 1px hairline for hover, and a translucent fill while this node is
 *    the active drop target.
 *
 * When [interactive] is false (slot subtrees — ADR-0019 scopes selection to `TreePath`, which
 * cannot address named slots) the chrome renders nothing: no registration, no gestures.
 */
@Composable
internal fun NodeChrome(
    node: UiNode,
    path: TreePath,
    workspace: EditorWorkspaceState,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!interactive) {
        Box(modifier) { content() }
        return
    }

    val chrome = LocalChromeColors.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            workspace.hovered = path
        } else if (workspace.hovered == path) {
            workspace.hovered = null
        }
    }
    DisposableEffect(path) {
        onDispose {
            workspace.dropRegistry.unregister(path)
            if (workspace.hovered == path) workspace.hovered = null
        }
    }

    val isSelected = workspace.isSelected(path)
    val isHoverHighlighted = workspace.isHoverHighlighted(path)
    val isDropTarget = workspace.dragState.active?.container == path

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                workspace.dropRegistry.register(
                    path = path,
                    isContainer = node is Container,
                    bounds = coords.boundsInWindow(),
                )
            }
            .hoverable(interactions)
            .pointerInput(path) {
                detectTapGestures(onTap = { workspace.selection = path })
            }
            .drawWithContent {
                if (isDropTarget) {
                    drawRect(color = chrome.dropTargetFill)
                }
                drawContent()
                when {
                    isSelected -> drawRect(
                        color = chrome.selection,
                        style = Stroke(width = SELECTION_STROKE_PX),
                    )
                    isHoverHighlighted -> drawRect(
                        color = chrome.hover,
                        style = Stroke(width = HOVER_STROKE_PX),
                    )
                }
            },
    ) {
        content()
    }
}

private const val SELECTION_STROKE_PX = 2f
private const val HOVER_STROKE_PX = 1f
