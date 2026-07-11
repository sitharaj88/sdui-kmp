package dev.sdui.kmp.widgetsnativemap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.NativeSurfaceFactory

/**
 * [NativeSurfaceFactory] for the `sdui.map` kind.
 *
 * One concrete class lives in each platform source set:
 * - **androidMain**: wraps Google Maps Compose (`com.google.maps.android:maps-compose`) on top
 *   of the Maps SDK for Android. Honours the runtime's API-key gate — when no real key is
 *   configured the factory renders the same fallback the desktop / Wasm targets use, so a
 *   missing key never crashes a host that registered the factory.
 * - **iosMain**: wraps `MKMapView` via `UIKitView` interop (Compose Multiplatform 1.7+).
 * - **jvmMain (desktop)** and **wasmJsMain**: render a Material 3 `Card` listing the marker
 *   titles and the `kind=sdui.map` caption. Desktop / web have no first-class map widget,
 *   so the fallback is intentional and stable.
 *
 * Hosts construct the factory via [MapSurfaceFactoryConfig.instance]; the `expect` shape lets
 * each platform inject its own dependencies (Android `Context`, iOS `CLLocationCoordinate2D`)
 * without surfacing them in `commonMain`.
 */
public expect class MapSurfaceFactory : NativeSurfaceFactory {
    override val kind: String
    override val handledVersions: ClosedRange<SchemaVersion>

    @Composable
    override fun Render(surface: NativeSurface, modifier: Modifier)

    public companion object {
        /**
         * Build a default platform [MapSurfaceFactory]. [requireApiKey] is consulted only on
         * Android — when `true` the factory falls back to [MapSurfacePlaceholder] unless a
         * real Google Maps API key is present in `AndroidManifest.xml`. Other targets ignore
         * the parameter (they always render the cross-platform placeholder for desktop/web,
         * or the native MKMapView for iOS).
         */
        public fun instance(requireApiKey: Boolean = true): MapSurfaceFactory
    }
}

/**
 * Static [NativeSurfaceFactory.kind] constant. Kept on a separate object so test code can
 * reference it without instantiating a platform factory (the iOS / Android constructors pull
 * in platform-only types).
 */
public object MapSurfaceKind {
    /** Wire identifier matching the `kind` string emitted by the sample server. */
    public const val ID: String = "sdui.map"

    /** Inclusive [SchemaVersion] range this factory family handles. M0 ships V1..V1. */
    public val HandledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion.V1
}
