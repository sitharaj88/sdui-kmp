package dev.sdui.kmp.widgetsnativemap

import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SduiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Round-trip + decode tests for [MapSurfaceConfig]. The wire format is what server authors
 * type into the `nativeSurface` DSL — guard it with a dedicated test so a typo in
 * @[kotlinx.serialization.SerialName] doesn't silently break end-to-end demos.
 */
class MapSurfaceConfigTest {

    @Test
    fun config_roundtrips_with_markers() {
        val original = MapSurfaceConfig(
            centerLat = 37.7749,
            centerLng = -122.4194,
            zoom = 14f,
            markers = listOf(
                MapMarker(id = "m1", lat = 37.78, lng = -122.41, title = "Pickup"),
                MapMarker(id = "m2", lat = 37.77, lng = -122.42, title = "Dropoff"),
            ),
        )
        val json = SduiJson.encodeToString(MapSurfaceConfig.serializer(), original)
        val decoded = SduiJson.decodeFromString(MapSurfaceConfig.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun config_decodes_snake_case_keys_on_the_wire() {
        val json = """
            {
                "center_lat": 37.7749,
                "center_lng": -122.4194,
                "zoom": 13,
                "markers": [
                    {"id": "m1", "lat": 37.78, "lng": -122.41, "title": "Pickup"}
                ]
            }
        """.trimIndent()
        val decoded = SduiJson.decodeFromString(MapSurfaceConfig.serializer(), json)
        assertEquals(37.7749, decoded.centerLat)
        assertEquals(-122.4194, decoded.centerLng)
        assertEquals(13f, decoded.zoom)
        assertEquals(1, decoded.markers.size)
        assertEquals("Pickup", decoded.markers[0].title)
    }

    @Test
    fun zoom_defaults_when_absent() {
        val json = """{"center_lat": 0.0, "center_lng": 0.0}"""
        val decoded = SduiJson.decodeFromString(MapSurfaceConfig.serializer(), json)
        assertEquals(MapSurfaceConfig.DEFAULT_ZOOM, decoded.zoom)
        assertTrue(decoded.markers.isEmpty())
    }

    @Test
    fun decoder_returns_null_for_invalid_payload() {
        val surface = NativeSurface(
            id = NodeId("bad"),
            kind = MapSurfaceKind.ID,
            // Missing required centerLat / centerLng — decode should return null, not throw.
            config = buildJsonObject { put("zoom", JsonPrimitive(10)) },
        )
        assertNull(decodeMapSurfaceConfig(surface))
    }

    @Test
    fun decoder_decodes_real_native_surface_payload() {
        val surface = NativeSurface(
            id = NodeId("map"),
            kind = MapSurfaceKind.ID,
            config = buildJsonObject {
                put("center_lat", JsonPrimitive(37.7749))
                put("center_lng", JsonPrimitive(-122.4194))
                put("zoom", JsonPrimitive(13))
                put(
                    "markers",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive("pickup"))
                                put("lat", JsonPrimitive(37.78))
                                put("lng", JsonPrimitive(-122.41))
                                put("title", JsonPrimitive("Pickup"))
                            },
                        )
                    },
                )
            },
        )
        val config = decodeMapSurfaceConfig(surface)
        assertNotNull(config)
        assertEquals(37.7749, config.centerLat)
        assertEquals(1, config.markers.size)
        assertEquals("pickup", config.markers[0].id)
    }

    @Test
    fun kind_constant_matches_factory_contract() {
        // Sample server emits "sdui.map" — guard against accidental rename.
        assertEquals("sdui.map", MapSurfaceKind.ID)
    }
}
