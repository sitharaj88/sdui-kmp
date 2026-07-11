package dev.sdui.kmp.sample.server

import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.NavKind
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.ScreenMetadata
import dev.sdui.kmp.protocol.Value

/**
 * Server-emitted screen that demonstrates the `widgets-nav` `NavHost(kind = Tab)` renderer.
 *
 * The screen root is a single [NavHost] node — clients with `widgets-nav` registered render
 * the Material 3 tab chrome and load each tab's nested screen via
 * `LocalNestedScreenSourceFactory`. Older clients without the renderer fall back to the
 * `UnknownUiNode` path (no fallback supplied today; a future revision may set one).
 *
 * The route map keys are the user-visible tab labels (the renderer ships a stub icon, since
 * the protocol does not carry per-tab icon tokens yet) and the values are the standard
 * server screen routes that already exist in [Main.kt].
 *
 * Constructed directly (not via the `screen { }` DSL) because [dev.sdui.kmp.server.ScreenScope]
 * has no helper for [NavHost] yet — adding one is gated on a separate review touching
 * `:server`. When that helper lands, the body below collapses to a single DSL call.
 */
internal fun tabsScreen(): Screen = Screen(
    id = ScreenId("tabs"),
    version = SchemaVersion.V1,
    root = NavHost(
        id = NodeId("tabs/host"),
        kind = NavKind.Tab,
        initial = Destination.TabSwitch("home"),
        routes = linkedMapOf(
            "home" to "home",
            "feed" to "feed",
            "tracking" to "tracking",
        ),
    ),
    metadata = ScreenMetadata(
        title = Value.ofString("Tabs demo"),
        analyticsName = "tabs_screen",
    ),
)
