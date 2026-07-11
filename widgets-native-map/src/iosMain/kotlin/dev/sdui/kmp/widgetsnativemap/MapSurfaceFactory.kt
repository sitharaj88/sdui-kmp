package dev.sdui.kmp.widgetsnativemap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.NativeSurfaceFactory
import kotlin.math.pow
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapView
import platform.MapKit.MKPointAnnotation

/**
 * iOS actual of [MapSurfaceFactory]. Wraps `MKMapView` via Compose Multiplatform's
 * [UIKitView] interop.
 *
 * The translation from `zoom` (Google Maps log-scale 0..21) to MapKit's region span is a
 * coarse approximation: zoom 13 ≈ 5 km radius, doubling per level. Production wiring would
 * use a Mercator-aware conversion; for the demo a span computed from `zoom` is enough to
 * land on the correct neighbourhood when the surface first renders.
 *
 * Bindings and events are not yet wired on iOS — this initial cut renders the static
 * config. Future revisions hook MKMapView delegate callbacks for `markerTapped`.
 */
@OptIn(ExperimentalForeignApi::class)
public actual class MapSurfaceFactory internal constructor(
    @Suppress("UNUSED_PARAMETER") requireApiKey: Boolean,
) : NativeSurfaceFactory {
    actual override val kind: String = MapSurfaceKind.ID
    actual override val handledVersions: ClosedRange<SchemaVersion> = MapSurfaceKind.HandledVersions

    @Composable
    actual override fun Render(surface: NativeSurface, modifier: Modifier) {
        val config = decodeMapSurfaceConfig(surface)
        if (config == null) {
            MapSurfacePlaceholder(surface = surface, config = null, modifier = modifier)
            return
        }

        UIKitView(
            factory = { MKMapView() },
            modifier = modifier,
            update = { mapView ->
                applyConfig(mapView, config)
            },
        )
        // Re-apply on config change. UIKitView.update is itself keyed by recomposition, so
        // this LaunchedEffect is only here to make the dependency explicit and trigger when
        // the surface id changes between recompositions (e.g. server-driven re-routing).
        LaunchedEffect(surface.id, config) { /* no-op — update closure does the work */ }
    }

    public actual companion object {
        public actual fun instance(requireApiKey: Boolean): MapSurfaceFactory =
            MapSurfaceFactory(requireApiKey = requireApiKey)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun applyConfig(mapView: MKMapView, config: MapSurfaceConfig) {
    val center = CLLocationCoordinate2DMake(config.centerLat, config.centerLng)

    // Approximate zoom -> region span. zoom 13 ≈ 5km radius; each step doubles / halves.
    // Anything outside [1..20] clamps so MapKit doesn't reject the region.
    val clampedZoom = config.zoom.coerceIn(1f, 20f)
    val baseDistanceMeters = 5_000.0 // distance at zoom 13
    val factor = 2.0.pow((13.0 - clampedZoom).toDouble())
    val span = baseDistanceMeters * factor

    val region = MKCoordinateRegionMakeWithDistance(center, span, span)
    mapView.setRegion(region, animated = false)

    // Replace existing annotations with the freshly-decoded ones. MKMapView is mutable so
    // we clear and re-add rather than diffing — fine for the marker counts we expect.
    mapView.removeAnnotations(mapView.annotations)
    config.markers.forEach { marker ->
        val annotation = MKPointAnnotation()
        annotation.setCoordinate(CLLocationCoordinate2DMake(marker.lat, marker.lng))
        annotation.setTitle(marker.title)
        mapView.addAnnotation(annotation)
    }
}
