package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Covers the per-item use of [StateStore.child]: a LazyList renderer creates one node-scoped
 * child per visible row, seeded from the row's JSON. Writes inside a row must not leak into
 * sibling rows or the parent scope.
 */
class StateStoreItemScopingTest {

    @Test
    fun per_item_writes_do_not_leak_across_siblings() {
        val screen = StateStore()
        val row1 = screen.child(
            scope = StateScope.Node,
            initial = mapOf(
                StatePath("id") to JsonPrimitive("a"),
                StatePath("liked") to JsonPrimitive(false),
            ),
        )
        val row2 = screen.child(
            scope = StateScope.Node,
            initial = mapOf(
                StatePath("id") to JsonPrimitive("b"),
                StatePath("liked") to JsonPrimitive(false),
            ),
        )
        row1.update(StatePath("liked"), JsonPrimitive(true))
        assertEquals(JsonPrimitive(true), row1.read(StatePath("liked")))
        assertEquals(JsonPrimitive(false), row2.read(StatePath("liked")))
        assertNull(screen.read(StatePath("liked")))
    }

    @Test
    fun per_item_reads_fall_through_to_screen_scope() {
        val screen = StateStore(mapOf(StatePath("user.name") to JsonPrimitive("alice")))
        val row = screen.child(
            scope = StateScope.Node,
            initial = mapOf(StatePath("title") to JsonPrimitive("hi")),
        )
        assertEquals(JsonPrimitive("alice"), row.read(StatePath("user.name")))
        assertEquals(JsonPrimitive("hi"), row.read(StatePath("title")))
    }

    @Test
    fun child_scope_is_node() {
        val row = StateStore().child(StateScope.Node)
        assertEquals(StateScope.Node, row.scope)
    }
}
