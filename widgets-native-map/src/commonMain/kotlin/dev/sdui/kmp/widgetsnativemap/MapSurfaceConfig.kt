package dev.sdui.kmp.widgetsnativemap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format config for the `sdui.map` [dev.sdui.kmp.protocol.NativeSurface]. Decoded from
 * [dev.sdui.kmp.protocol.NativeSurface.config] (a `JsonObject`) on every render so that server
 * authors can change zoom or markers without a client release.
 *
 * Field naming matches the snake_case JSON keys the server emits via the `nativeSurface`
 * DSL — see [dev.sdui.kmp.sample.server.trackingScreen]. All numeric fields are doubles or
 * floats so values that arrive as JSON numbers decode cleanly without manual coercion.
 *
 * Schema is intentionally tiny — additive evolution applies the same way as the protocol
 * itself: new fields land with defaults, never required, never type-changed.
 *
 * @property centerLat Initial map center latitude (WGS-84 degrees, -90..90).
 * @property centerLng Initial map center longitude (WGS-84 degrees, -180..180).
 * @property zoom Initial zoom level. Google Maps caps at ~21; Apple Maps uses delta-latitude
 *   internally but the factory translates [zoom] to the closest equivalent region.
 * @property markers Static markers placed on the map at decode time. Dynamic markers should
 *   flow through [dev.sdui.kmp.protocol.NativeSurface.bindings] in a future revision.
 */
@Serializable
public data class MapSurfaceConfig(
    @SerialName("center_lat") public val centerLat: Double,
    @SerialName("center_lng") public val centerLng: Double,
    @SerialName("zoom") public val zoom: Float = DEFAULT_ZOOM,
    @SerialName("markers") public val markers: List<MapMarker> = emptyList(),
) {
    public companion object {
        /** Reasonable city-scale default zoom; matches the value used by the sample server. */
        public const val DEFAULT_ZOOM: Float = 13f
    }
}

/**
 * Single map pin. The [title] is shown in the marker's info window when a user taps it; the
 * [id] is echoed back into the runtime via the `markerTapped` event so server-side actions
 * can disambiguate which marker fired.
 *
 * @property id Stable identifier for the marker; surfaced in the `markerTapped` event payload.
 * @property lat Latitude in WGS-84 degrees.
 * @property lng Longitude in WGS-84 degrees.
 * @property title Human-readable title displayed in the marker's info window.
 */
@Serializable
public data class MapMarker(
    @SerialName("id") public val id: String,
    @SerialName("lat") public val lat: Double,
    @SerialName("lng") public val lng: Double,
    @SerialName("title") public val title: String,
)
