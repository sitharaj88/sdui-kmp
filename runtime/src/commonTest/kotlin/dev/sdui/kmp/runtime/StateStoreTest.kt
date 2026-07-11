package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive

class StateStoreTest {
    @Test
    fun reads_return_null_when_unset() {
        val store = StateStore()
        assertNull(store.read(StatePath("missing")))
    }

    @Test
    fun update_is_reflected_in_snapshot_and_read() {
        val store = StateStore()
        val path = StatePath("user.name")
        store.update(path, JsonPrimitive("alice"))
        assertEquals(JsonPrimitive("alice"), store.read(path))
        assertEquals(JsonPrimitive("alice"), store.snapshot.value[path])
    }

    @Test
    fun patch_applies_all_keys_in_one_step() {
        val store = StateStore()
        store.patch(
            mapOf(
                StatePath("a") to JsonPrimitive(1),
                StatePath("b") to JsonPrimitive(2),
            ),
        )
        assertEquals(JsonPrimitive(1), store.read(StatePath("a")))
        assertEquals(JsonPrimitive(2), store.read(StatePath("b")))
    }

    @Test
    fun child_is_a_distinct_store() {
        val store = StateStore()
        val child = store.child(StateScope.Screen)
        assertNotEquals(store, child)
        assertEquals(StateScope.Screen, child.scope)
    }

    @Test
    fun child_reads_fall_through_to_parent() {
        val root = StateStore(mapOf(StatePath("user.name") to JsonPrimitive("alice")))
        val screen = root.child(StateScope.Screen)
        assertEquals(JsonPrimitive("alice"), screen.read(StatePath("user.name")))
    }

    @Test
    fun child_writes_do_not_affect_parent() {
        val root = StateStore()
        val screen = root.child(StateScope.Screen)
        screen.update(StatePath("flag"), JsonPrimitive(true))
        assertEquals(JsonPrimitive(true), screen.read(StatePath("flag")))
        assertNull(root.read(StatePath("flag")))
    }

    @Test
    fun child_local_overrides_parent_for_same_key() {
        val root = StateStore(mapOf(StatePath("k") to JsonPrimitive("parent")))
        val screen = root.child(StateScope.Screen, mapOf(StatePath("k") to JsonPrimitive("child")))
        assertEquals(JsonPrimitive("child"), screen.read(StatePath("k")))
        assertEquals(JsonPrimitive("parent"), root.read(StatePath("k")))
    }

    @Test
    fun parent_updates_after_child_creation_are_visible_to_child() {
        val root = StateStore()
        val screen = root.child(StateScope.Screen)
        root.update(StatePath("later"), JsonPrimitive(42))
        assertEquals(JsonPrimitive(42), screen.read(StatePath("later")))
    }
}
