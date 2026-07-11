package dev.sdui.kmp.widgetsnativemap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.NativeSurfaceFactory

/**
 * Desktop (JVM) actual of [MapSurfaceFactory]. There is no first-class map widget on
 * Compose for Desktop, so this implementation always renders [MapSurfacePlaceholder] —
 * a Material 3 `Card` listing the marker titles.
 *
 * The factory still satisfies [NativeSurfaceFactory] so a host that registers it on
 * Desktop sees a graceful, deterministic fallback rather than the protocol's generic
 * "no factory registered" telemetry path. Both work; this one keeps the user-visible
 * UI consistent with the iOS / Android variants.
 */
public actual class MapSurfaceFactory internal constructor(
    @Suppress("UNUSED_PARAMETER") requireApiKey: Boolean,
) : NativeSurfaceFactory {
    actual override val kind: String = MapSurfaceKind.ID
    actual override val handledVersions: ClosedRange<SchemaVersion> = MapSurfaceKind.HandledVersions

    @Composable
    actual override fun Render(surface: NativeSurface, modifier: Modifier) {
        val config = decodeMapSurfaceConfig(surface)
        MapSurfacePlaceholder(surface = surface, config = config, modifier = modifier)
    }

    public actual companion object {
        public actual fun instance(requireApiKey: Boolean): MapSurfaceFactory =
            MapSurfaceFactory(requireApiKey = requireApiKey)
    }
}
