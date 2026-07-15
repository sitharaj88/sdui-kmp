package dev.sdui.kmp.studio.web.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.sdui.kmp.protocol.UiNode

/** Canvas device-width presets; `dp == null` means fill the available width. */
internal enum class CanvasWidthPreset(val label: String, val dp: Int?) {
    Phone("Phone", PHONE_WIDTH_DP),
    Tablet("Tablet", TABLET_WIDTH_DP),
    Fill("Fill", null),
}

/**
 * Shared state for the visual-editor workspace: the [TreeMutator], the current selection and
 * hover paths (shared between canvas and layers panel so highlighting stays in sync), the
 * canvas width preset, the live drag session, and the drop-target geometry registry.
 *
 * All mutations flow through [commit], which skips structurally-equal trees so no-op edits
 * (same-position drops, unchanged inspector commits) never pollute the undo history, and
 * re-validates the selection against the new tree.
 */
@Stable
internal class EditorWorkspaceState(val mutator: TreeMutator) {
    /** Currently selected node path, or null. */
    var selection: TreePath? by mutableStateOf(null)

    /** Currently hovered node path (canvas or layers row), or null. */
    var hovered: TreePath? by mutableStateOf(null)

    /** Canvas device-frame width. */
    var canvasWidth: CanvasWidthPreset by mutableStateOf(CanvasWidthPreset.Phone)

    /** Live drag session (palette spawn or canvas move). */
    val dragState: DragDropState = DragDropState()

    /** Window-rect registry for drop resolution and overlay drawing. */
    val dropRegistry: DropTargetRegistry = DropTargetRegistry()

    /** Layers-panel expansion state, keyed by the path's string form. Missing = expanded. */
    val expandedLayers: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /**
     * Replaces the tree with [newRoot] (one undo entry) unless it is structurally equal to the
     * current root, then drops the selection if it no longer resolves.
     */
    fun commit(newRoot: UiNode) {
        if (newRoot == mutator.current) return
        mutator.replace(newRoot)
        normalizeSelection()
    }

    /** Clears [selection] when it no longer resolves in the current tree. */
    fun normalizeSelection() {
        val current = selection ?: return
        if (mutator.current.childAt(current) == null) selection = null
    }

    /** True while [path] is the active selection. */
    fun isSelected(path: TreePath): Boolean = selection == path

    /** True while [path] is hovered and no drag is running (drags own the highlight). */
    fun isHoverHighlighted(path: TreePath): Boolean = hovered == path && !dragState.isDragging
}

private const val PHONE_WIDTH_DP = 390
private const val TABLET_WIDTH_DP = 768
