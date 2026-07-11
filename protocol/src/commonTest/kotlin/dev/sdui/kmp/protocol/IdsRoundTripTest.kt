package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class IdsRoundTripTest {
    private val json = Json

    @Test
    fun nodeId_roundtrips() {
        val id = NodeId("home.title")
        val encoded = json.encodeToString(NodeId.serializer(), id)
        assertEquals("\"home.title\"", encoded)
        assertEquals(id, json.decodeFromString(NodeId.serializer(), encoded))
    }

    @Test
    fun statePath_roundtrips_and_child_composes() {
        val path = StatePath("user").child("profile").child("name")
        assertEquals("user.profile.name", path.value)
        val encoded = json.encodeToString(StatePath.serializer(), path)
        assertEquals("\"user.profile.name\"", encoded)
        assertEquals(path, json.decodeFromString(StatePath.serializer(), encoded))
    }

    @Test
    fun statePath_root_child_has_no_leading_dot() {
        assertEquals("first", StatePath.Root.child("first").value)
    }

    @Test
    fun schemaVersion_roundtrips_and_orders() {
        val v1 = SchemaVersion.V1
        val v2 = SchemaVersion(2)
        assertEquals("1", json.encodeToString(SchemaVersion.serializer(), v1))
        assertEquals(v2, json.decodeFromString(SchemaVersion.serializer(), "2"))
        check(v1 < v2)
    }

    @Test
    fun screenId_roundtrips() {
        val id = ScreenId("home")
        val encoded = json.encodeToString(ScreenId.serializer(), id)
        assertEquals("\"home\"", encoded)
        assertEquals(id, json.decodeFromString(ScreenId.serializer(), encoded))
    }
}
