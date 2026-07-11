package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class NativeAndMediaRoundTripTest {
    private val json: Json = SduiJson

    @Test
    fun native_surface_roundtrips_with_config_bindings_and_events() {
        val node: UiNode = NativeSurface(
            id = NodeId("map"),
            kind = "sdui.map",
            config = buildJsonObject {
                put("center_lat", JsonPrimitive(37.7))
                put("center_lng", JsonPrimitive(-122.4))
                put("zoom", JsonPrimitive(13))
            },
            bindings = mapOf("driver_position" to StatePath("driver.location")),
            events = mapOf(
                "markerTapped" to listOf(
                    Action.Navigate(Destination.ScreenDest("/driver")),
                ),
            ),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun native_surface_with_fallback_preserves_subtree() {
        val node: UiNode = NativeSurface(
            id = NodeId("map"),
            kind = "sdui.map",
            fallback = Text(
                id = NodeId("map_fallback"),
                content = Value.ofString("Map unavailable."),
            ),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun image_roundtrips_with_content_scale() {
        val node: UiNode = Image(
            id = NodeId("hero"),
            source = Value.ofString("asset://hero.png"),
            contentDescription = Value.ofString("Hero banner"),
            contentScale = ContentScale.Crop,
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun async_image_roundtrips_with_placeholder_and_error_subtrees() {
        val node: UiNode = AsyncImage(
            id = NodeId("avatar"),
            url = Value.Bind(StatePath("driver.avatar")),
            contentDescription = Value.ofString("Driver"),
            contentScale = ContentScale.Fit,
            placeholder = Text(
                id = NodeId("avatar.placeholder"),
                content = Value.ofString("…"),
            ),
            error = Text(
                id = NodeId("avatar.error"),
                content = Value.ofString("?"),
            ),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun every_content_scale_roundtrips() {
        ContentScale.entries.forEach {
            val s = ContentScale.serializer()
            assertEquals(it, json.decodeFromString(s, json.encodeToString(s, it)))
        }
    }
}
