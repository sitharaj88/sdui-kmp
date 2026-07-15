package dev.sdui.kmp.studio.web.editor

import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TreeOpsTest {

    @Test
    fun childAtRootReturnsSelf() {
        val node: UiNode = Column(id = NodeId("root"), since = SchemaVersion.V1)
        assertEquals(node, node.childAt(emptyList()))
    }

    @Test
    fun childAtTraversesContainer() {
        val leaf = Text(id = NodeId("leaf"), content = Value.ofString("hi"))
        val root: UiNode = Column(
            id = NodeId("root"),
            since = SchemaVersion.V1,
            children = listOf(leaf),
        )
        assertEquals(leaf, root.childAt(listOf(0)))
    }

    @Test
    fun childAtReturnsNullForOutOfRange() {
        val root: UiNode = Column(id = NodeId("root"), since = SchemaVersion.V1)
        assertNull(root.childAt(listOf(0)))
    }

    @Test
    fun childAtReturnsNullDescendingIntoLeaf() {
        val leaf: UiNode = Text(id = NodeId("leaf"), content = Value.ofString("hi"))
        assertNull(leaf.childAt(listOf(0)))
    }

    @Test
    fun replacingAtSwapsLeaf() {
        val original = Text(id = NodeId("a"), content = Value.ofString("old"))
        val replacement = Text(id = NodeId("a"), content = Value.ofString("new"))
        val root: UiNode = Column(
            id = NodeId("root"),
            since = SchemaVersion.V1,
            children = listOf(original),
        )
        val updated = root.replacingAt(listOf(0), replacement)
        assertNotNull(updated)
        assertEquals(replacement, updated.childAt(listOf(0)))
    }

    @Test
    fun insertingChildAppendsToContainer() {
        val root: UiNode = Column(id = NodeId("root"), since = SchemaVersion.V1)
        val leaf = Text(id = NodeId("a"), content = Value.ofString("hi"))
        val updated = root.insertingChild(emptyList(), leaf)
        assertNotNull(updated)
        val container = updated as Column
        assertEquals(1, container.children.size)
        assertEquals(leaf, container.children[0])
    }

    @Test
    fun insertingChildAtNestedContainer() {
        val inner = Column(id = NodeId("inner"), since = SchemaVersion.V1)
        val root: UiNode = Column(
            id = NodeId("root"),
            since = SchemaVersion.V1,
            children = listOf(inner),
        )
        val leaf = Text(id = NodeId("a"), content = Value.ofString("hi"))
        val updated = root.insertingChild(listOf(0), leaf)
        assertNotNull(updated)
        val nested = updated.childAt(listOf(0)) as Column
        assertEquals(1, nested.children.size)
    }

    @Test
    fun removingAtRemovesChild() {
        val a = Text(id = NodeId("a"), content = Value.ofString("a"))
        val b = Text(id = NodeId("b"), content = Value.ofString("b"))
        val root: UiNode = Column(
            id = NodeId("root"),
            since = SchemaVersion.V1,
            children = listOf(a, b),
        )
        val updated = root.removingAt(listOf(0))
        assertNotNull(updated)
        val container = updated as Column
        assertEquals(1, container.children.size)
        assertEquals(b, container.children[0])
    }

    @Test
    fun removingAtRootReturnsNull() {
        val root: UiNode = Column(id = NodeId("root"), since = SchemaVersion.V1)
        assertNull(root.removingAt(emptyList()))
    }

    @Test
    fun insertingChildIntoLeafReturnsNull() {
        val leaf: UiNode = Text(id = NodeId("a"), content = Value.ofString("a"))
        val newNode = Text(id = NodeId("b"), content = Value.ofString("b"))
        assertNull(leaf.insertingChild(emptyList(), newNode))
    }

    @Test
    fun replacingAtInvalidPathReturnsNull() {
        val root: UiNode = Column(id = NodeId("root"), since = SchemaVersion.V1)
        val replacement = Text(id = NodeId("a"), content = Value.ofString("a"))
        assertNull(root.replacingAt(listOf(5), replacement))
    }

    @Test
    fun newSpawnedNodesHaveUniqueIds() {
        val ids = (1..10).map { newText().id.value }.toSet()
        assertTrue(ids.size > 1)
    }

    // -- insertingChildAt ------------------------------------------------------------------

    @Test
    fun insertingChildAtZeroPrepends() {
        val a = text("a")
        val root: UiNode = column("root", a)
        val b = text("b")
        val updated = root.insertingChildAt(emptyList(), 0, b)
        assertNotNull(updated)
        assertEquals(listOf(b, a), (updated as Column).children)
    }

    @Test
    fun insertingChildAtMiddleInserts() {
        val a = text("a")
        val c = text("c")
        val root: UiNode = column("root", a, c)
        val b = text("b")
        val updated = root.insertingChildAt(emptyList(), 1, b)
        assertNotNull(updated)
        assertEquals(listOf(a, b, c), (updated as Column).children)
    }

    @Test
    fun insertingChildAtSizeAppends() {
        val a = text("a")
        val root: UiNode = column("root", a)
        val b = text("b")
        val updated = root.insertingChildAt(emptyList(), 1, b)
        assertNotNull(updated)
        assertEquals(listOf(a, b), (updated as Column).children)
    }

    @Test
    fun insertingChildAtNegativeIndexReturnsNull() {
        val root: UiNode = column("root")
        assertNull(root.insertingChildAt(emptyList(), -1, text("a")))
    }

    @Test
    fun insertingChildAtBeyondSizeReturnsNull() {
        val root: UiNode = column("root")
        assertNull(root.insertingChildAt(emptyList(), 1, text("a")))
    }

    @Test
    fun insertingChildAtIntoLeafReturnsNull() {
        val leaf: UiNode = text("a")
        assertNull(leaf.insertingChildAt(emptyList(), 0, text("b")))
    }

    @Test
    fun insertingChildAtNestedPath() {
        val inner = column("inner", text("x"))
        val root: UiNode = column("root", inner)
        val updated = root.insertingChildAt(listOf(0), 0, text("y"))
        assertNotNull(updated)
        val nested = updated.childAt(listOf(0)) as Column
        assertEquals(2, nested.children.size)
        assertEquals("y", nested.children[0].id.value)
    }

    // -- isSelfOrDescendantOf --------------------------------------------------------------

    @Test
    fun selfIsSelfOrDescendant() {
        assertTrue(listOf(1, 2).isSelfOrDescendantOf(listOf(1, 2)))
    }

    @Test
    fun childIsDescendant() {
        assertTrue(listOf(1, 2, 0).isSelfOrDescendantOf(listOf(1, 2)))
    }

    @Test
    fun siblingIsNotDescendant() {
        assertTrue(!listOf(1, 3).isSelfOrDescendantOf(listOf(1, 2)))
    }

    // -- movingNode ------------------------------------------------------------------------

    @Test
    fun movingNodeReorderForwardWithinParent() {
        val a = text("a")
        val b = text("b")
        val c = text("c")
        val root: UiNode = column("root", a, b, c)
        // Move a (index 0) to sit after c: pre-removal index 3.
        val updated = root.movingNode(from = listOf(0), toContainer = emptyList(), index = 3)
        assertNotNull(updated)
        assertEquals(listOf(b, c, a), (updated as Column).children)
    }

    @Test
    fun movingNodeReorderBackwardWithinParent() {
        val a = text("a")
        val b = text("b")
        val c = text("c")
        val root: UiNode = column("root", a, b, c)
        // Move c (index 2) to the front.
        val updated = root.movingNode(from = listOf(2), toContainer = emptyList(), index = 0)
        assertNotNull(updated)
        assertEquals(listOf(c, a, b), (updated as Column).children)
    }

    @Test
    fun movingNodeAcrossContainers() {
        val a = text("a")
        val target = column("target")
        val root: UiNode = column("root", a, target)
        val updated = root.movingNode(from = listOf(0), toContainer = listOf(1), index = 0)
        assertNotNull(updated)
        val rootColumn = updated as Column
        assertEquals(1, rootColumn.children.size)
        val landed = updated.childAt(listOf(0, 0))
        assertEquals(a, landed)
    }

    @Test
    fun movingNodeIntoContainerAfterSourceAdjustsPath() {
        // target sits AFTER the moved node in the same parent, so its index shifts down by one
        // once the source is removed. The op must still land the node inside target.
        val a = text("a")
        val target = column("target")
        val tail = text("tail")
        val root: UiNode = column("root", a, target, tail)
        val updated = root.movingNode(from = listOf(0), toContainer = listOf(1), index = 0)
        assertNotNull(updated)
        val landed = updated.childAt(listOf(0, 0))
        assertEquals(a, landed)
        assertEquals("tail", updated.childAt(listOf(1))?.id?.value)
    }

    @Test
    fun movingNodeIntoOwnDescendantReturnsNull() {
        val inner = column("inner")
        val outer = column("outer", inner)
        val root: UiNode = column("root", outer)
        assertNull(root.movingNode(from = listOf(0), toContainer = listOf(0, 0), index = 0))
    }

    @Test
    fun movingNodeIntoItselfReturnsNull() {
        val inner = column("inner")
        val root: UiNode = column("root", inner)
        assertNull(root.movingNode(from = listOf(0), toContainer = listOf(0), index = 0))
    }

    @Test
    fun movingRootReturnsNull() {
        val root: UiNode = column("root")
        assertNull(root.movingNode(from = emptyList(), toContainer = emptyList(), index = 0))
    }

    @Test
    fun movingNodeToSamePositionIsStructurallyEqual() {
        val a = text("a")
        val b = text("b")
        val root: UiNode = column("root", a, b)
        // Dropping a back into slot 0 must round-trip to an equal tree so callers can skip
        // the history entry.
        val updated = root.movingNode(from = listOf(0), toContainer = emptyList(), index = 0)
        assertNotNull(updated)
        assertEquals(root, updated)
    }

    @Test
    fun adjustedDestinationShiftsSiblingIndex() {
        val (path, index) = adjustedDestination(from = listOf(0), toContainer = listOf(2), index = 1)
        assertEquals(listOf(1), path)
        assertEquals(1, index)
    }

    @Test
    fun adjustedDestinationShiftsSameParentIndex() {
        val (path, index) = adjustedDestination(from = listOf(1), toContainer = emptyList(), index = 3)
        assertEquals(emptyList(), path)
        assertEquals(2, index)
    }

    private fun text(id: String) = Text(id = NodeId(id), content = Value.ofString(id))

    private fun column(id: String, vararg children: UiNode) =
        Column(id = NodeId(id), since = SchemaVersion.V1, children = children.toList())
}
