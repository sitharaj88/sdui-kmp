package dev.sdui.kmp.widgetsnativemap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.NativeSurfaceFactory

/**
 * Wasm/JS actual of [MapSurfaceFactory]. Browsers have first-class map libraries (Google
 * Maps JS, Mapbox GL JS) but they are not yet bridgeable from Compose for Web, so this
 * variant renders [MapSurfacePlaceholder] — a Material 3 `Card` listing the markers.
 *
 * Hosts targeting the web that need a real map should embed it outside the Compose tree
 * and link to it from a server-emitted [dev.sdui.kmp.protocol.Action.Navigate]; the
 * placeholder makes that gap obvious during development.
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
