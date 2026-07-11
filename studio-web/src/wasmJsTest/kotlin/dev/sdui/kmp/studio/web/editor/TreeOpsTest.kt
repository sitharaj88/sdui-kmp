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
}
