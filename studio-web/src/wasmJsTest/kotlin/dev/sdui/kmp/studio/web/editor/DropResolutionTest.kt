package dev.sdui.kmp.studio.web.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class DropResolutionTest {

    private fun node(path: TreePath, isContainer: Boolean, bounds: Rect) =
        RegisteredNode(path = path, isContainer = isContainer, bounds = bounds)

    @Test
    fun pointerOutsideEverythingResolvesNull() {
        val nodes = listOf(node(emptyList(), true, Rect(0f, 0f, 100f, 100f)))
        assertNull(resolveDropLocation(Offset(500f, 500f), nodes, draggedPath = null))
    }

    @Test
    fun leafNodesAreNotDropTargets() {
        val nodes = listOf(node(listOf(0), false, Rect(0f, 0f, 100f, 100f)))
        assertNull(resolveDropLocation(Offset(50f, 50f), nodes, draggedPath = null))
    }

    @Test
    fun deepestContainerWins() {
        val nodes = listOf(
            node(emptyList(), true, Rect(0f, 0f, 200f, 200f)),
            node(listOf(0), true, Rect(20f, 20f, 180f, 180f)),
        )
        val location = resolveDropLocation(Offset(100f, 100f), nodes, draggedPath = null)
        assertNotNull(location)
        assertEquals(listOf(0), location.container)
    }

    @Test
    fun draggedSubtreeIsExcluded() {
        val nodes = listOf(
            node(emptyList(), true, Rect(0f, 0f, 200f, 200f)),
            node(listOf(0), true, Rect(20f, 20f, 180f, 180f)),
            node(listOf(0, 0), true, Rect(40f, 40f, 160f, 160f)),
        )
        // Dragging the container at [0]: neither it nor its inner container may win; the
        // root remains the only candidate.
        val location = resolveDropLocation(Offset(100f, 100f), nodes, draggedPath = listOf(0))
        assertNotNull(location)
        assertEquals(emptyList(), location.container)
    }

    @Test
    fun insertionIndexCountsChildMidpointsAbovePointer() {
        val nodes = listOf(
            node(emptyList(), true, Rect(0f, 0f, 100f, 300f)),
            node(listOf(0), false, Rect(0f, 0f, 100f, 100f)),
            node(listOf(1), false, Rect(0f, 100f, 100f, 200f)),
            node(listOf(2), false, Rect(0f, 200f, 100f, 300f)),
        )
        // Pointer below child 0's midpoint (50) and above child 1's (150) → index 1.
        val location = resolveDropLocation(Offset(50f, 120f), nodes, draggedPath = null)
        assertNotNull(location)
        assertEquals(1, location.index)
    }

    @Test
    fun emptyContainerResolvesIndexZero() {
        val nodes = listOf(node(emptyList(), true, Rect(0f, 0f, 100f, 100f)))
        val location = resolveDropLocation(Offset(50f, 50f), nodes, draggedPath = null)
        assertNotNull(location)
        assertEquals(0, location.index)
        assertEquals(emptyList(), location.container)
    }

    @Test
    fun layersDropBeforeRowMapsToRowIndex() {
        val rows = listOf(
            node(emptyList(), true, Rect(0f, 0f, 100f, 20f)),
            node(listOf(0), false, Rect(0f, 20f, 100f, 40f)),
            node(listOf(1), false, Rect(0f, 40f, 100f, 60f)),
        )
        // Pointer in the top half of row [1] → insert before it: (parent=[], index=1).
        val location = resolveLayersDrop(Offset(50f, 44f), rows, draggedPath = null)
        assertNotNull(location)
        assertEquals(emptyList(), location.container)
        assertEquals(1, location.index)
    }

    @Test
    fun layersDropAfterRowMapsToNextIndex() {
        val rows = listOf(
            node(listOf(0), false, Rect(0f, 20f, 100f, 40f)),
            node(listOf(1), false, Rect(0f, 40f, 100f, 60f)),
        )
        // Pointer in the bottom half of row [1] → insert after it: index 2.
        val location = resolveLayersDrop(Offset(50f, 56f), rows, draggedPath = null)
        assertNotNull(location)
        assertEquals(2, location.index)
    }

    @Test
    fun layersDropExcludesDraggedSubtree() {
        val rows = listOf(
            node(listOf(0), true, Rect(0f, 20f, 100f, 40f)),
            node(listOf(0, 0), false, Rect(0f, 40f, 100f, 60f)),
        )
        // Every row belongs to the dragged subtree → nowhere to drop.
        assertNull(resolveLayersDrop(Offset(50f, 45f), rows, draggedPath = listOf(0)))
    }

    @Test
    fun layersDropOutsidePanelResolvesNull() {
        val rows = listOf(node(listOf(0), false, Rect(0f, 20f, 100f, 40f)))
        assertNull(resolveLayersDrop(Offset(300f, 30f), rows, draggedPath = null))
    }
}
