package dev.sdui.kmp.studio.web.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** What is currently being dragged over the editor. */
internal sealed interface DragPayload {
    /** A fresh node about to be spawned from the palette. */
    data class NewNode(val descriptor: WidgetDescriptor) : DragPayload

    /** An existing canvas node being moved; [path] addresses it in the pre-move tree. */
    data class ExistingNode(val path: TreePath) : DragPayload
}

/**
 * Live drag-session state. Observed by the canvas overlay (ghost + drop indicator) and by
 * node chrome (to suppress hover highlights while a drag is active).
 */
internal class DragDropState {
    /** Non-null while a drag is in progress. */
    var payload: DragPayload? by mutableStateOf(null)

    /** Current pointer position in window coordinates. */
    var pointerInWindow: Offset by mutableStateOf(Offset.Zero)

    /** Where the payload would land if dropped now; null while over no valid container. */
    var active: DropLocation? by mutableStateOf(null)

    val isDragging: Boolean get() = payload != null

    /** Clears the whole session (drop committed or cancelled). */
    fun clear() {
        payload = null
        active = null
    }
}

/** A drop destination: which container, which child gap, and where to draw the indicator. */
internal data class DropLocation(
    val container: TreePath,
    val index: Int,
    val indicatorBounds: Rect,
)

/** One canvas node's registered geometry. */
internal data class RegisteredNode(
    val path: TreePath,
    val isContainer: Boolean,
    val bounds: Rect,
)

/**
 * Snapshot-state registry of node window-rects, written from `onGloballyPositioned` callbacks
 * (safe: positioned callbacks run outside composition) and read by the drag resolver and the
 * canvas overlay. Keyed by the path's string form so unregistration on dispose is exact.
 */
internal class DropTargetRegistry {
    private val nodes = mutableStateMapOf<String, RegisteredNode>()

    fun register(path: TreePath, isContainer: Boolean, bounds: Rect) {
        nodes[path.key()] = RegisteredNode(path, isContainer, bounds)
    }

    fun unregister(path: TreePath) {
        nodes.remove(path.key())
    }

    /** The registered geometry for [path], if currently laid out. */
    fun boundsOf(path: TreePath): Rect? = nodes[path.key()]?.bounds

    /** Point-in-time copy for pure resolution. */
    fun snapshot(): List<RegisteredNode> = nodes.values.toList()

    /** Drops every registration — used when the canvas tree is re-seeded. */
    fun clear() {
        nodes.clear()
    }
}

private fun TreePath.key(): String = joinToString(separator = "/")

/**
 * Pure drop resolution (unit-testable without UI), per ADR-0019:
 *
 *  1. Candidates are registered **containers** whose bounds contain [pointer].
 *  2. When moving an existing node ([draggedPath] non-null), the dragged node and its whole
 *     subtree are excluded — a container may never be dropped into itself or a descendant.
 *  3. The deepest candidate wins (longest path; ties broken by smallest area).
 *  4. The insertion index is the number of the winner's direct children whose vertical
 *     midpoint sits above the pointer (Columns are vertical; revisit for Row containers).
 *  5. The indicator rect is the horizontal gap line at that index.
 */
@Suppress("ReturnCount")
internal fun resolveDropLocation(
    pointer: Offset,
    nodes: List<RegisteredNode>,
    draggedPath: TreePath?,
): DropLocation? {
    val winner = nodes
        .asSequence()
        .filter { it.isContainer && it.bounds.contains(pointer) }
        .filter { draggedPath == null || !it.path.isSelfOrDescendantOf(draggedPath) }
        .sortedWith(
            compareByDescending<RegisteredNode> { it.path.size }
                .thenBy { it.bounds.width * it.bounds.height },
        )
        .firstOrNull() ?: return null

    val childRects = nodes
        .filter { it.path.size == winner.path.size + 1 && it.path.take(winner.path.size) == winner.path }
        .sortedBy { it.path.last() }

    val index = childRects.count { it.bounds.center.y <= pointer.y }

    val lineY = when {
        childRects.isEmpty() -> winner.bounds.center.y
        index == 0 -> childRects.first().bounds.top
        index >= childRects.size -> childRects.last().bounds.bottom
        else -> childRects[index].bounds.top
    }
    val indicator = Rect(
        left = winner.bounds.left + DROP_INDICATOR_INSET,
        top = lineY - DROP_INDICATOR_HALF_THICKNESS,
        right = winner.bounds.right - DROP_INDICATOR_INSET,
        bottom = lineY + DROP_INDICATOR_HALF_THICKNESS,
    )
    return DropLocation(container = winner.path, index = index, indicatorBounds = indicator)
}

/**
 * Drop resolution for the layers panel: rows are a flat vertical list, so the gap above or
 * below the row under the pointer maps directly to `(parent, index)` — no container
 * hit-testing needed. The root row (empty path) is skipped; cycle rules are enforced by
 * excluding rows inside the dragged subtree, and again by `movingNode` (defence in depth).
 */
@Suppress("ReturnCount")
internal fun resolveLayersDrop(
    pointer: Offset,
    rows: List<RegisteredNode>,
    draggedPath: TreePath?,
): DropLocation? {
    val candidates = rows
        .filter { it.path.isNotEmpty() }
        .filter { draggedPath == null || !it.path.isSelfOrDescendantOf(draggedPath) }
        .sortedBy { it.bounds.top }
    if (candidates.isEmpty()) return null
    val panelLeft = candidates.minOf { it.bounds.left }
    val panelRight = candidates.maxOf { it.bounds.right }
    if (pointer.x < panelLeft || pointer.x > panelRight) return null
    if (pointer.y < candidates.first().bounds.top || pointer.y > candidates.last().bounds.bottom) return null

    val row = candidates.lastOrNull { it.bounds.top <= pointer.y } ?: candidates.first()
    val before = pointer.y <= row.bounds.center.y
    val parent = row.path.dropLast(1)
    val index = if (before) row.path.last() else row.path.last() + 1
    val lineY = if (before) row.bounds.top else row.bounds.bottom
    return DropLocation(
        container = parent,
        index = index,
        indicatorBounds = Rect(
            left = row.bounds.left,
            top = lineY - DROP_INDICATOR_HALF_THICKNESS,
            right = row.bounds.right,
            bottom = lineY + DROP_INDICATOR_HALF_THICKNESS,
        ),
    )
}

private const val DROP_INDICATOR_INSET = 6f
private const val DROP_INDICATOR_HALF_THICKNESS = 1.5f
