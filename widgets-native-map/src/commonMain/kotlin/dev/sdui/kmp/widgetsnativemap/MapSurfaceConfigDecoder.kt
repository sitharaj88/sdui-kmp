package dev.sdui.kmp.widgetsnativemap

import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SduiJson
import kotlinx.serialization.json.Json

/**
 * Decodes a [MapSurfaceConfig] from a [NativeSurface.config] payload using the protocol's
 * [SduiJson]. Centralised so every platform actual decodes the same way and so unit tests
 * can exercise it without touching Compose.
 *
 * Returns `null` when the JSON does not match the schema (unknown field with strict mode is
 * already off via [SduiJson] — this only fails on missing required fields). Callers should
 * render the [NativeSurface.fallback] subtree on null, never throw — invariant #3.
 */
public fun decodeMapSurfaceConfig(
    surface: NativeSurface,
    json: Json = SduiJson,
): MapSurfaceConfig? = runCatching {
    json.decodeFromJsonElement(MapSurfaceConfig.serializer(), surface.config)
}.getOrNull()
