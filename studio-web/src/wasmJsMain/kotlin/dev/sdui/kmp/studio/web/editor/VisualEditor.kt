package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Container
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.studio.web.components.SegmentOption
import dev.sdui.kmp.studio.web.components.SegmentedControl
import dev.sdui.kmp.studio.web.components.StudioPanel
import dev.sdui.kmp.studio.web.components.ToolbarButton
import dev.sdui.kmp.studio.web.theme.StudioIcons

/**
 * Top-level visual editor surface used by the Editor tab in `ScreenDetailView`.
 *
 * Layout: toolbar (undo/redo, canvas width presets, delete, "Apply to JSON") over three
 * panels — a fixed left rail (widget palette above the layers panel), the WYSIWYG
 * [EditorCanvas] filling the center, and the property inspector on the right. Fixed-dp side
 * panels keep the drop-registry geometry stable during drags.
 *
 * The component owns an [EditorWorkspaceState] keyed by [screen]'s id, seeded from the
 * screen's root. Per the original task brief we DO NOT auto-encode the tree on every
 * mutation — operators see local changes immediately on the canvas and push them back to the
 * JSON pane explicitly via "Apply to JSON".
 */
@Composable
@Suppress("FunctionNaming", "LongMethod")
public fun VisualEditor(
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = remember(screen.id) { EditorWorkspaceState(TreeMutator(initial = screen.root)) }
    LaunchedEffect(screen.root) {
        // If the JSON pane edits the tree out from under us we re-seed. Structural (not
        // reference) equality matters here: "Apply to JSON" round-trips the tree through the
        // debounced decoder, which produces an equal-but-new instance — resetting on `!==`
        // would wipe the undo history on every Apply.
        if (workspace.mutator.current != screen.root) {
            workspace.mutator.reset(screen.root)
            workspace.dropRegistry.clear()
            workspace.normalizeSelection()
        }
    }
    val current = workspace.mutator.current
    val selectedNode = workspace.selection?.let { current.childAt(it) }

    Column(modifier = modifier.fillMaxSize()) {
        EditorToolbar(
            workspace = workspace,
            onApply = { onScreenChange(screen.copy(root = workspace.mutator.current)) },
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.width(PALETTE_WIDTH).fillMaxHeight(),
            ) {
                StudioPanel(
                    title = "WIDGETS",
                    modifier = Modifier.weight(PALETTE_WEIGHT).fillMaxWidth(),
                ) {
                    WidgetPalette(
                        onAdd = { node -> workspace.insertFromPalette(node) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                StudioPanel(
                    title = "LAYERS",
                    contentPadding = 6.dp,
                    modifier = Modifier.weight(LAYERS_WEIGHT).fillMaxWidth(),
                ) {
                    LayersPanel(workspace = workspace, modifier = Modifier.fillMaxSize())
                }
            }
            EditorCanvas(
                workspace = workspace,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StudioPanel(
                title = "INSPECTOR",
                modifier = Modifier.width(INSPECTOR_WIDTH).fillMaxHeight(),
            ) {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    PropertyInspector(
                        selected = selectedNode,
                        onChange = { replacement ->
                            val path = workspace.selection ?: return@PropertyInspector
                            val updated = workspace.mutator.current.replacingAt(path, replacement)
                            if (updated != null) workspace.commit(updated)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    workspace: EditorWorkspaceState,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolbarIconButton(
            icon = StudioIcons.Undo,
            description = "Undo",
            enabled = workspace.mutator.canUndo,
            onClick = {
                workspace.mutator.undo()
                workspace.normalizeSelection()
            },
        )
        ToolbarIconButton(
            icon = StudioIcons.Redo,
            description = "Redo",
            enabled = workspace.mutator.canRedo,
            onClick = {
                workspace.mutator.redo()
                workspace.normalizeSelection()
            },
        )
        SegmentedControl(
            options = CanvasWidthPreset.entries.map { SegmentOption(label = it.label) },
            selectedIndex = workspace.canvasWidth.ordinal,
            onSelect = { workspace.canvasWidth = CanvasWidthPreset.entries[it] },
            modifier = Modifier.padding(start = 6.dp),
        )
        Box(Modifier.weight(1f))
        ToolbarIconButton(
            icon = StudioIcons.Delete,
            description = "Delete selected node",
            enabled = workspace.selection?.isNotEmpty() == true,
            onClick = { workspace.deleteSelection() },
        )
        ToolbarButton(text = "Apply to JSON", onClick = onApply)
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(TOOLBAR_ICON_BUTTON_SIZE)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ICON_ALPHA)
            },
            modifier = Modifier.size(TOOLBAR_ICON_SIZE),
        )
    }
}

/**
 * Inserts a palette-spawned [node]: into the selected container, after the selected leaf, or
 * appended to the root. The new node becomes the selection so the inspector opens on it.
 */
internal fun EditorWorkspaceState.insertFromPalette(node: UiNode) {
    val root = mutator.current
    val selectedPath = selection
    val selectedNode = selectedPath?.let { root.childAt(it) }
    val (updated, newPath) = when {
        selectedPath != null && selectedNode is Container -> {
            val index = selectedNode.children.size
            root.insertingChildAt(selectedPath, index, node) to (selectedPath + index)
        }
        selectedPath != null && selectedPath.isNotEmpty() -> {
            val parent = selectedPath.dropLast(1)
            val index = selectedPath.last() + 1
            root.insertingChildAt(parent, index, node) to (parent + index)
        }
        else -> {
            val rootContainer = root as? Container
            val index = rootContainer?.children?.size ?: 0
            root.insertingChildAt(emptyList(), index, node) to listOf(index)
        }
    }
    if (updated != null) {
        commit(updated)
        selection = newPath
    }
}

/** Removes the selected node (root is not deletable) and clears the selection. */
internal fun EditorWorkspaceState.deleteSelection() {
    val path = selection ?: return
    if (path.isEmpty()) return
    val updated = mutator.current.removingAt(path) ?: return
    commit(updated)
    selection = null
}

private val PALETTE_WIDTH = 260.dp
private val INSPECTOR_WIDTH = 320.dp
private const val PALETTE_WEIGHT = 0.55f
private const val LAYERS_WEIGHT = 0.45f
private val TOOLBAR_ICON_BUTTON_SIZE = 32.dp
private val TOOLBAR_ICON_SIZE = 16.dp
private const val DISABLED_ICON_ALPHA = 0.4f

/**
 * Human-readable one-line summary of [node] shared by the layers panel and overlays.
 */
internal fun describeNode(node: UiNode): String {
    val typeName = node::class.simpleName ?: "Node"
    val suffix = when (node) {
        is dev.sdui.kmp.protocol.Text -> previewText(node.content).displayString().takeIf { it.isNotEmpty() }
            ?.let { " \"${it.take(SUMMARY_MAX)}\"" }.orEmpty()
        is dev.sdui.kmp.protocol.Button -> previewText(node.label).displayString().takeIf { it.isNotEmpty() }
            ?.let { " [${it.take(SUMMARY_MAX)}]" }.orEmpty()
        else -> ""
    }
    return "$typeName$suffix"
}

private const val SUMMARY_MAX = 24
