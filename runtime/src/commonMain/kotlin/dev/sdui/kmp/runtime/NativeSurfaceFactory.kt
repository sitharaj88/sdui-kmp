package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SchemaVersion

/**
 * Factory that renders a [NativeSurface] of a specific [kind] on this platform.
 *
 * Examples — `sdui.map` on Android wraps a Google Maps `MapView`; the same kind on iOS wraps
 * an Apple Maps `MKMapView`. The [kind] string is the only bit of the protocol the factory
 * speaks — everything else flows through [NativeSurface.config] (JSON), [NativeSurface.bindings]
 * (state paths), and [NativeSurface.events] (actions fired back into the dispatcher).
 */
public interface NativeSurfaceFactory {
    public val kind: String
    public val handledVersions: ClosedRange<SchemaVersion>

    @Composable
    public fun Render(surface: NativeSurface, modifier: Modifier)
}

/**
 * Kind-keyed registry of [NativeSurfaceFactory] implementations for this client. Host apps
 * build one at startup listing every native kind they want to support.
 *
 * Lookups are O(1) by string; if no factory is registered for a kind — or the registered
 * factory's [NativeSurfaceFactory.handledVersions] excludes the node's [NativeSurface.since] —
 * [factoryFor] returns null and the [NativeSurface] renders its [fallback] tree.
 */
public class NativeSurfaceRegistry private constructor(
    private val factories: Map<String, NativeSurfaceFactory>,
    public val clientVersion: SchemaVersion,
) {
    public fun factoryFor(kind: String, nodeVersion: SchemaVersion): NativeSurfaceFactory? {
        val factory = factories[kind] ?: return null
        return if (clientVersion in factory.handledVersions && nodeVersion >= factory.handledVersions.start) factory else null
    }

    public class Builder(private val clientVersion: SchemaVersion) {
        private val factories: MutableMap<String, NativeSurfaceFactory> = mutableMapOf()
        public fun register(factory: NativeSurfaceFactory): Builder = apply {
            factories[factory.kind] = factory
        }
        public fun build(): NativeSurfaceRegistry = NativeSurfaceRegistry(factories.toMap(), clientVersion)
    }

    public companion object {
        public val Empty: NativeSurfaceRegistry = NativeSurfaceRegistry(emptyMap(), SchemaVersion.V1)

        public fun build(
            clientVersion: SchemaVersion = SchemaVersion.V1,
            configure: Builder.() -> Unit,
        ): NativeSurfaceRegistry = Builder(clientVersion).apply(configure).build()
    }
}

/** Active [NativeSurfaceRegistry]. Defaults to empty so unknown kinds always fall back safely. */
public val LocalNativeSurfaceRegistry: ProvidableCompositionLocal<NativeSurfaceRegistry> =
    staticCompositionLocalOf { NativeSurfaceRegistry.Empty }
