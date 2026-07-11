package dev.sdui.kmp.protocol.fixtures

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.UnknownUiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FixtureContractTest {

    @Test
    fun every_node_fixture_roundtrips_byte_identical() {
        NodeFixtures.all.forEach { fx ->
            val encoded = SduiJson.encodeToString(UiNode.serializer(), fx.node)
            val reencoded = SduiJson.encodeToString(
                UiNode.serializer(),
                SduiJson.decodeFromString(UiNode.serializer(), encoded),
            )
            assertEquals(encoded, reencoded, "fixture ${fx.name}: re-encoded drifted")
        }
    }

    @Test
    fun every_node_fixture_encodes_to_its_declared_json() {
        NodeFixtures.all.forEach { fx ->
            val actual = SduiJson.encodeToString(UiNode.serializer(), fx.node)
            assertEquals(fx.json, actual, "fixture ${fx.name}: declared JSON does not match encoder output")
        }
    }

    @Test
    fun every_node_fixture_decodes_to_its_declared_model() {
        NodeFixtures.all.forEach { fx ->
            val decoded = SduiJson.decodeFromString(UiNode.serializer(), fx.json)
            assertEquals(fx.node, decoded, "fixture ${fx.name}: decoded model does not match")
        }
    }

    @Test
    fun no_node_fixture_decodes_to_unknown() {
        NodeFixtures.all.forEach { fx ->
            val decoded = SduiJson.decodeFromString(UiNode.serializer(), fx.json)
            check(decoded !is UnknownUiNode) { "fixture ${fx.name}: decoded to UnknownUiNode" }
        }
    }

    @Test
    fun screen_fixtures_roundtrip() {
        ScreenFixtures.all.forEach { fx ->
            val encoded = SduiJson.encodeToString(Screen.serializer(), fx.screen)
            assertEquals(fx.json, encoded, "screen fixture ${fx.name}")
            assertEquals(fx.screen, SduiJson.decodeFromString(Screen.serializer(), encoded))
        }
    }

    @Test
    fun forward_compat_unknown_with_fallback_decodes_to_unknown_sentinel() {
        val decoded = SduiJson.decodeFromString(UiNode.serializer(), ForwardCompatFixtures.UNKNOWN_WITH_FALLBACK)
        val unknown = assertIs<UnknownUiNode>(decoded)
        assertEquals("radical_new_widget", unknown.originalType)
        assertNotNull(unknown.fallback)
    }

    @Test
    fun forward_compat_unknown_without_fallback_has_null_fallback() {
        val decoded = SduiJson.decodeFromString(UiNode.serializer(), ForwardCompatFixtures.UNKNOWN_WITHOUT_FALLBACK)
        val unknown = assertIs<UnknownUiNode>(decoded)
        assertEquals("something", unknown.originalType)
        assertNull(unknown.fallback)
    }

    @Test
    fun tree_patch_fixture_decodes_to_three_ops() {
        val patch = SduiJson.decodeFromString(
            TreePatch.serializer(),
            PatchAndLiveFixtures.TREE_PATCH_REPLACE_APPEND_REMOVE,
        )
        assertEquals(3, patch.ops.size)
    }

    @Test
    fun live_state_update_fixture_decodes_with_two_paths() {
        val event = SduiJson.decodeFromString(LiveEvent.serializer(), PatchAndLiveFixtures.LIVE_STATE_UPDATE)
        val state = assertIs<LiveEvent.StateUpdate>(event)
        assertEquals(2, state.updates.size)
    }

    @Test
    fun live_tree_patch_fixture_decodes_to_single_remove_op() {
        val event = SduiJson.decodeFromString(LiveEvent.serializer(), PatchAndLiveFixtures.LIVE_TREE_PATCH)
        val wrapper = assertIs<LiveEvent.TreePatchEvent>(event)
        assertEquals(1, wrapper.patch.ops.size)
    }
}
