package dev.sdui.kmp.widgetsnav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.NavKind
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.NodeRenderer
import kotlin.reflect.KClass

/**
 * Single [NodeRenderer] for [NavHost]. Dispatches by [NavHost.kind] at composition time:
 *
 *  - [NavKind.Tab] — Material 3 [androidx.compose.material3.NavigationBar] + a body slot that
 *    hosts a nested [dev.sdui.kmp.runtime.SduiHost] for the active tab. Per-tab state is kept
 *    isolated by giving each tab its own [dev.sdui.kmp.runtime.ScreenSource].
 *  - [NavKind.BottomSheet] — a Material 3 modal bottom sheet showing the route at
 *    [NavHost.initial]. Dismissal is server-driven (the sheet body is expected to ship a
 *    "Cancel" button that fires `Action.Navigate(Destination.Back())`).
 *  - [NavKind.Stack] — thin pass-through that renders the route at [NavHost.initial] inline.
 *    Real stack chrome already lives in the host-level [dev.sdui.kmp.runtime.Navigator]; this
 *    renderer exists only so a server-emitted `NavHost(kind = Stack)` does not fall back to
 *    an `UnknownUiNode`.
 *
 * Per the protocol contract, this renderer never throws. Missing wiring (no
 * [dev.sdui.kmp.runtime.LocalNestedScreenSourceFactory]) renders an inline diagnostic in place
 * of pixels instead.
 */
public object NavHostRenderer : NodeRenderer<NavHost> {
    override val nodeClass: KClass<NavHost> = NavHost::class
    override val handledVersions: ClosedRange<SchemaVersion> =
        SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: NavHost, modifier: Modifier) {
        when (node.kind) {
            NavKind.Tab -> TabNavRenderer(node = node, modifier = modifier)
            NavKind.BottomSheet -> BottomSheetNavRenderer(node = node, modifier = modifier)
            NavKind.Stack -> StackNavRenderer(node = node, modifier = modifier)
            // A NavKind added by a newer server coerces to Unspecified on decode; render it as a
            // plain stack so an unknown chrome kind never blanks the screen.
            NavKind.Unspecified -> StackNavRenderer(node = node, modifier = modifier)
        }
    }
}

/**
 * Renders the route at [NavHost.initial] (or the first route in [NavHost.routes]) as a plain
 * nested [dev.sdui.kmp.runtime.SduiHost]. [NavKind.Stack] overlaps with the host-level
 * navigator we already have, so this is intentionally minimal.
 */
@Composable
@Suppress("FunctionNaming") // Compose composables are PascalCase by convention.
internal fun StackNavRenderer(node: NavHost, modifier: Modifier) {
    val initialRoute = (node.initial as? Destination.ScreenDest)?.route
        ?: (node.initial as? Destination.Modal)?.route
        ?: node.routes.values.firstOrNull()
    if (initialRoute == null) {
        EmptyNavPlaceholder(modifier = modifier, message = "NavHost has no routes to render")
        return
    }
    NestedSduiHost(route = initialRoute, modifier = modifier)
}

/** Inline diagnostic shown when the renderer cannot proceed (missing factory, empty routes). */
@Composable
@Suppress("FunctionNaming") // Compose composables are PascalCase by convention.
internal fun EmptyNavPlaceholder(modifier: Modifier, message: String) {
    Box(
        modifier = modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message)
    }
}
