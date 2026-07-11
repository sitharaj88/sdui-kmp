package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class TreePatchEngineTest {

    private fun baseScreen() = Screen(
        id = ScreenId("home"),
        version = SchemaVersion.V1,
        root = Column(
            id = NodeId("root"),
            children = listOf(
                Text(id = NodeId("t1"), content = Value.ofString("one")),
                Text(id = NodeId("t2"), content = Value.ofString("two")),
                Text(id = NodeId("t3"), content = Value.ofString("three")),
            ),
        ),
    )

    @Test
    fun replace_swaps_targeted_node() {
        val screen = baseScreen()
        val patched = screen.apply(
            TreePatch(
                ops = listOf(
                    PatchOp.Replace(
                        nodeId = NodeId("t2"),
                        node = Text(id = NodeId("t2"), content = Value.ofString("TWO")),
                    ),
                ),
            ),
        )
        val root = assertIs<Column>(patched.root)
        assertEquals("TWO", ((root.children[1] as Text).content as Value.Literal<*>).value.toString().trim('"'))
        // Unrelated siblings are untouched
        assertEquals(NodeId("t1"), root.children[0].id)
        assertEquals(NodeId("t3"), root.children[2].id)
    }

    @Test
    fun append_adds_children_to_targeted_container() {
        val screen = baseScreen()
        val patched = screen.apply(
            TreePatch(
                ops = listOf(
                    PatchOp.Append(
                        parentId = NodeId("root"),
                        nodes = listOf(Text(id = NodeId("t4"), content = Value.ofString("four"))),
                    ),
                ),
            ),
        )
        val root = assertIs<Column>(patched.root)
        assertEquals(4, root.children.size)
        assertEquals(NodeId("t4"), root.children[3].id)
    }

    @Test
    fun remove_drops_every_listed_id() {
        val screen = baseScreen()
        val patched = screen.apply(
            TreePatch(ops = listOf(PatchOp.Remove(nodeIds = listOf(NodeId("t1"), NodeId("t3"))))),
        )
        val root = assertIs<Column>(patched.root)
        assertEquals(1, root.children.size)
        assertEquals(NodeId("t2"), root.children[0].id)
    }

    @Test
    fun ops_apply_in_order_composing_results() {
        val screen = baseScreen()
        val patched = screen.apply(
            TreePatch(
                ops = listOf(
                    PatchOp.Remove(nodeIds = listOf(NodeId("t1"))),
                    PatchOp.Append(
                        parentId = NodeId("root"),
                        nodes = listOf(Text(id = NodeId("t4"), content = Value.ofString("four"))),
                    ),
                    PatchOp.Replace(
                        nodeId = NodeId("t2"),
                        node = Text(id = NodeId("t2"), content = Value.ofString("TWO")),
                    ),
                ),
            ),
        )
        val root = assertIs<Column>(patched.root)
        assertEquals(3, root.children.size)
        assertEquals(NodeId("t2"), root.children[0].id)
        assertEquals(NodeId("t3"), root.children[1].id)
        assertEquals(NodeId("t4"), root.children[2].id)
    }

    @Test
    fun unknown_target_is_silent_no_op() {
        val screen = baseScreen()
        val patched = screen.apply(
            TreePatch(
                ops = listOf(
                    PatchOp.Replace(
                        nodeId = NodeId("does_not_exist"),
                        node = Text(id = NodeId("whatever"), content = Value.ofString("x")),
                    ),
                ),
            ),
        )
        // No mutation → same Screen instance is returned so equal contents & identity.
        assertSame(screen, patched)
    }

    @Test
    fun empty_patch_returns_same_screen_instance() {
        val screen = baseScreen()
        assertSame(screen, screen.apply(TreePatch(ops = emptyList())))
    }

    /** Builds a linear `Column`-in-`Column` chain [depth] levels deep with a `Text` leaf. */
    private fun deepScreen(depth: Int): Screen {
        var node: UiNode = Text(id = NodeId("leaf"), content = Value.ofString("bottom"))
        for (level in depth downTo 1) {
            node = Column(id = NodeId("n$level"), children = listOf(node))
        }
        return Screen(id = ScreenId("deep"), version = SchemaVersion.V1, root = node)
    }

    @Test
    fun pathologically_deep_tree_does_not_overflow_the_stack() {
        // Far deeper than any stack could recurse — without the depth budget the traversal below
        // would StackOverflow. The Remove targets an id that never appears, forcing a full descent.
        val screen = deepScreen(50_000)
        val patched = screen.apply(
            TreePatch(ops = listOf(PatchOp.Remove(nodeIds = listOf(NodeId("does_not_exist"))))),
        )
        // Traversal stops at the budget and finds no match, so the tree is returned untouched.
        assertSame(screen, patched)
    }

    @Test
    fun target_beyond_the_depth_budget_is_a_silent_no_op() {
        // Node "n5" sits at level 5; a maxDepth of 2 refuses to descend far enough to reach it.
        val screen = deepScreen(5)
        val patched = screen.apply(
            TreePatch(
                ops = listOf(
                    PatchOp.Replace(
                        nodeId = NodeId("n5"),
                        node = Text(id = NodeId("n5"), content = Value.ofString("replaced")),
                    ),
                ),
            ),
            maxDepth = 2,
        )
        assertSame(screen, patched)
    }

    @Test
    fun target_within_the_depth_budget_still_applies() {
        val screen = deepScreen(5)
        val patched = screen.apply(
            TreePatch(
                ops = listOf(
                    PatchOp.Replace(
                        nodeId = NodeId("n5"),
                        node = Text(id = NodeId("n5"), content = Value.ofString("replaced")),
                    ),
                ),
            ),
            maxDepth = 10,
        )
        // The replacement took effect: descend to n4 (its parent) and confirm its child is now Text.
        var node: UiNode = patched.root
        repeat(3) { node = (node as Column).children.single() }
        assertIs<Text>((node as Column).children.single())
    }
}
