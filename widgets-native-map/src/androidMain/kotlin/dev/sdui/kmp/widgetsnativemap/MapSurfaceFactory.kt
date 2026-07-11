package dev.sdui.kmp.widgetsnativemap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.LocalActionDispatcher
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NativeSurfaceFactory
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Android actual of [MapSurfaceFactory]. Wraps Google Maps Compose around the protocol's
 * [NativeSurface] node.
 *
 * Construction goes through [MapSurfaceFactoryConfig.instance] so a host can opt into either
 * the real `GoogleMap` rendering (real Maps API key configured in `AndroidManifest.xml`) or
 * the cross-platform [MapSurfacePlaceholder] (placeholder key still in place — the default
 * for the sample app and any host that has not signed up for a key yet). This keeps the
 * factory safe to register unconditionally on every Android build flavour.
 *
 * Wiring contract:
 * - [NativeSurface.config] decodes to [MapSurfaceConfig]; failures render the placeholder.
 * - [NativeSurface.bindings] keys `center_lat` / `center_lng` / `zoom` (when present) drive
 *   the camera from the runtime state store on every recomposition.
 * - [NativeSurface.events]['markerTapped'] fires through [LocalActionDispatcher]; the tapped
 *   marker's `id` is written to `markerTapped.id` in the local state store before dispatch
 *   so an action like `Action.UpdateState` can observe which marker was tapped.
 */
public actual class MapSurfaceFactory internal constructor(
    private val requireApiKey: Boolean,
) : NativeSurfaceFactory {
    actual override val kind: String = MapSurfaceKind.ID
    actual override val handledVersions: ClosedRange<SchemaVersion> = MapSurfaceKind.HandledVersions

    @Composable
    actual override fun Render(surface: NativeSurface, modifier: Modifier) {
        val config = decodeMapSurfaceConfig(surface)
        if (config == null || (requireApiKey && !hasRealApiKey())) {
            // Either the JSON didn't decode or the host has not configured a real Google
            // Maps API key — render the cross-platform placeholder rather than crashing or
            // showing a blank gray box.
            MapSurfacePlaceholder(surface = surface, config = config, modifier = modifier)
            return
        }
        AndroidMapBody(surface = surface, config = config, modifier = modifier)
    }

    public actual companion object {
        /**
         * Build a default Android [MapSurfaceFactory]. [requireApiKey] gates the real Google
         * Maps render — when `true` (default), the factory falls back to [MapSurfacePlaceholder]
         * if no real `com.google.android.geo.API_KEY` meta-data is present in the host
         * `AndroidManifest.xml`. Hosts that have a key set this `false`.
         */
        public actual fun instance(requireApiKey: Boolean): MapSurfaceFactory =
            MapSurfaceFactory(requireApiKey = requireApiKey)
    }
}

/**
 * Read-only check that `<meta-data android:name="com.google.android.geo.API_KEY" .../>` in
 * the host `AndroidManifest.xml` is set to a non-placeholder string. The placeholder
 * `REPLACE_WITH_YOUR_KEY` is what the sample ships with — and we render the cross-platform
 * placeholder rather than trying to load tiles when we see it, so the sample never crashes.
 */
@Composable
private fun hasRealApiKey(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        runCatching {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_META_DATA,
            )
            val key = ai.metaData?.getString("com.google.android.geo.API_KEY")
            !key.isNullOrBlank() && key != PLACEHOLDER_API_KEY
        }.getOrDefault(false)
    }
}

/** The well-known placeholder string documented in [MapSurfaceFactoryConfig]. */
internal const val PLACEHOLDER_API_KEY: String = "REPLACE_WITH_YOUR_KEY"

@Composable
private fun AndroidMapBody(
    surface: NativeSurface,
    config: MapSurfaceConfig,
    modifier: Modifier,
) {
    val store = LocalStateStore.current
    val dispatcher = LocalActionDispatcher.current
    val scope = rememberCoroutineScope()

    val cameraPositionState: CameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(config.centerLat, config.centerLng),
            config.zoom,
        )
    }

    // Drive the camera from runtime state when bindings are wired. Reads fall through to
    // the config defaults if a binding key is unset.
    val centerLat = surface.bindings["center_lat"]
        ?.let { (store.read(it) as? JsonPrimitive)?.content?.toDoubleOrNull() }
        ?: config.centerLat
    val centerLng = surface.bindings["center_lng"]
        ?.let { (store.read(it) as? JsonPrimitive)?.content?.toDoubleOrNull() }
        ?: config.centerLng
    val zoom = surface.bindings["zoom"]
        ?.let { (store.read(it) as? JsonPrimitive)?.content?.toFloatOrNull() }
        ?: config.zoom

    LaunchedEffect(centerLat, centerLng, zoom) {
        cameraPositionState.position = CameraPosition.fromLatLngZoom(
            LatLng(centerLat, centerLng),
            zoom,
        )
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
    ) {
        config.markers.forEach { marker ->
            Marker(
                state = MarkerState(position = LatLng(marker.lat, marker.lng)),
                title = marker.title,
                onClick = {
                    val actions = surface.events["markerTapped"].orEmpty()
                    if (actions.isNotEmpty()) {
                        // Echo the marker id into the local state store so server-authored
                        // actions can branch on it. Per-marker scoping is left to the action
                        // graph itself (Action.When, Action.UpdateState path templates).
                        store.update(
                            dev.sdui.kmp.protocol.StatePath("markerTapped.id"),
                            JsonPrimitive(marker.id),
                        )
                        scope.launch {
                            actions.forEach { dispatcher.dispatch(it) }
                        }
                    }
                    // Returning false lets Maps Compose still show the default info window.
                    false
                },
            )
        }
    }
}
