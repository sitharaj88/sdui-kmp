package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class TreePatchAndLiveEventRoundTripTest {
    private val json: Json = SduiJson

    @Test
    fun patch_op_replace_roundtrips() {
        val op: PatchOp = PatchOp.Replace(
            nodeId = NodeId("greeting"),
            node = Text(id = NodeId("greeting"), content = Value.ofString("new")),
        )
        assertEquals(op, json.decodeFromString(PatchOp.serializer(), json.encodeToString(PatchOp.serializer(), op)))
    }

    @Test
    fun patch_op_append_roundtrips() {
        val op: PatchOp = PatchOp.Append(
            parentId = NodeId("list"),
            nodes = listOf(Text(id = NodeId("row"), content = Value.ofString("hi"))),
        )
        assertEquals(op, json.decodeFromString(PatchOp.serializer(), json.encodeToString(PatchOp.serializer(), op)))
    }

    @Test
    fun patch_op_remove_roundtrips() {
        val op: PatchOp = PatchOp.Remove(nodeIds = listOf(NodeId("a"), NodeId("b")))
        assertEquals(op, json.decodeFromString(PatchOp.serializer(), json.encodeToString(PatchOp.serializer(), op)))
    }

    @Test
    fun tree_patch_with_mixed_ops_roundtrips() {
        val patch = TreePatch(
            ops = listOf(
                PatchOp.Replace(NodeId("t"), Text(id = NodeId("t"), content = Value.ofString("updated"))),
                PatchOp.Append(NodeId("root"), listOf(Text(id = NodeId("new"), content = Value.ofString("yo")))),
                PatchOp.Remove(listOf(NodeId("old"))),
            ),
        )
        assertEquals(
            patch,
            json.decodeFromString(TreePatch.serializer(), json.encodeToString(TreePatch.serializer(), patch)),
        )
    }

    @Test
    fun live_event_state_update_roundtrips() {
        val event: LiveEvent = LiveEvent.StateUpdate(
            updates = mapOf(
                StatePath("ticker") to JsonPrimitive(42),
                StatePath("message") to JsonPrimitive("hello"),
            ),
        )
        assertEquals(
            event,
            json.decodeFromString(LiveEvent.serializer(), json.encodeToString(LiveEvent.serializer(), event)),
        )
    }

    @Test
    fun live_event_tree_patch_roundtrips() {
        val event: LiveEvent = LiveEvent.TreePatchEvent(
            patch = TreePatch(
                ops = listOf(PatchOp.Remove(listOf(NodeId("x")))),
            ),
        )
        assertEquals(
            event,
            json.decodeFromString(LiveEvent.serializer(), json.encodeToString(LiveEvent.serializer(), event)),
        )
    }
}
