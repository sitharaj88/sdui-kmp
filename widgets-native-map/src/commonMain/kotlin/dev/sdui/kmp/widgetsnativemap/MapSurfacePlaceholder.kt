package dev.sdui.kmp.widgetsnativemap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.NativeSurface

/**
 * Cross-platform fallback Composable used by the desktop / Wasm factories and by the Android
 * factory when its Maps API key is the placeholder string. Renders a Material 3 `Card` with
 * a "Map placeholder" caption and the marker titles as a list — readable, never throws, and
 * obvious enough that a developer running against a misconfigured environment notices.
 *
 * The visual treatment is intentionally plain so a missing or blank [config] surface still
 * shows the [NativeSurface.kind] label.
 */
@Composable
public fun MapSurfacePlaceholder(
    surface: NativeSurface,
    config: MapSurfaceConfig?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Map placeholder for kind=${surface.kind}")
            if (config == null) {
                Text("(config could not be decoded — rendering empty placeholder)")
            } else {
                Text("center=${config.centerLat}, ${config.centerLng}  zoom=${config.zoom}")
                if (config.markers.isEmpty()) {
                    Text("(no markers)")
                } else {
                    config.markers.forEach { marker ->
                        Text("- ${marker.title}")
                    }
                }
            }
        }
    }
}
