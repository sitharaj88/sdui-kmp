package dev.sdui.kmp.widgetsnav

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.sdui.kmp.runtime.LocalNativeSurfaceRegistry
import dev.sdui.kmp.runtime.LocalNestedScreenSourceFactory
import dev.sdui.kmp.runtime.LocalRegistry
import dev.sdui.kmp.runtime.LocalTelemetry
import dev.sdui.kmp.runtime.ScreenSource
import dev.sdui.kmp.runtime.SduiHost
import dev.sdui.kmp.runtime.StackNavigator

/**
 * Mounts a self-contained [SduiHost] for a nested navigation route.
 *
 * Reads the registry, telemetry, native-surface registry, and any in-scope dispatcher hints
 * from the parent composition, then asks [LocalNestedScreenSourceFactory] to build a
 * transport-bound [ScreenSource] for [route]. Each nested host owns its own [StackNavigator]
 * and (implicitly, via the inner [SduiHost]) its own [dev.sdui.kmp.runtime.StateStore], so
 * tabbed state cannot leak between tabs.
 *
 * If no factory is provided the function renders an inline diagnostic — never throws — per
 * VISION.md invariant #3.
 */
@Composable
@Suppress("FunctionNaming") // Compose composables are PascalCase by convention.
internal fun NestedSduiHost(
    route: String,
    modifier: Modifier = Modifier,
) {
    val factory = LocalNestedScreenSourceFactory.current
    if (factory == null) {
        EmptyNavPlaceholder(
            modifier = modifier,
            message = "No LocalNestedScreenSourceFactory provided; nested route '$route' cannot load.",
        )
        return
    }
    val registry = LocalRegistry.current
    val telemetry = LocalTelemetry.current
    val nativeSurfaces = LocalNativeSurfaceRegistry.current
    // Each nested host gets its own ScreenSource so per-tab state (scroll, selection) does
    // not leak across tabs. `remember(route, factory)` keys on the factory identity too so
    // hosts that swap implementations at runtime get a fresh source.
    val source: ScreenSource = remember(route, factory) { factory(route) }
    // NOTE: we deliberately do not wire a `DisposableEffect` to close [source]: the public
    // `ScreenSource` interface has no `close()` method (concrete implementations like
    // `HttpScreenSource` do, but calling that requires a transport-specific cast which would
    // make widgets-nav transport-aware). Hosts that need lifecycle-aware nested transports
    // can wrap their factory to return a delegating `ScreenSource` that tears itself down on
    // last-subscriber. Tab-switch leaks of completed network jobs are bounded by the parent
    // application scope.
    // Each tab gets its own navigator so back navigation inside one tab doesn't pop another.
    val nestedNavigator = remember(route) { StackNavigator(initial = route) }
    // SduiHost does not accept a Modifier (it always paints into Modifier.fillMaxSize()), so
    // we wrap it in a Box that carries the padding/sizing handed in by the parent Scaffold
    // or ModalBottomSheet content slot.
    Box(modifier = modifier) {
        SduiHost(
            source = source,
            registry = registry,
            navigator = nestedNavigator,
            telemetry = telemetry,
            nativeSurfaceRegistry = nativeSurfaces,
        )
    }
}
