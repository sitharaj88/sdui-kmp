package dev.sdui.kmp.runtime

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.sdui.kmp.protocol.MAX_UI_TREE_DEPTH

/** Active [WidgetRegistry]. Must be provided before any call to [RenderNode]. */
public val LocalRegistry: ProvidableCompositionLocal<WidgetRegistry> =
    staticCompositionLocalOf { error("LocalRegistry was not provided. Wrap content in SduiHost.") }

/** Active [StateStore]. */
public val LocalStateStore: ProvidableCompositionLocal<StateStore> =
    staticCompositionLocalOf { error("LocalStateStore was not provided. Wrap content in SduiHost.") }

/** Active [ActionDispatcher]. */
public val LocalActionDispatcher: ProvidableCompositionLocal<ActionDispatcher> =
    staticCompositionLocalOf { error("LocalActionDispatcher was not provided. Wrap content in SduiHost.") }

/** Active [SduiTelemetry]. Defaults to [NoopTelemetry] so tests and early samples need not wire one. */
public val LocalTelemetry: ProvidableCompositionLocal<SduiTelemetry> =
    staticCompositionLocalOf { NoopTelemetry }

/**
 * Current render depth of the node being composed by [RenderNode]. The root is `0`; each nested
 * [RenderNode] call (into a child or a fallback) sees one greater. [RenderNode] uses it to stop
 * recursing once [LocalMaxRenderDepth] is reached, so a pathologically deep tree cannot overflow
 * the stack. Implementation detail — hosts configure the ceiling via [LocalMaxRenderDepth].
 */
internal val LocalRenderDepth: ProvidableCompositionLocal<Int> =
    staticCompositionLocalOf { 0 }

/**
 * Maximum depth [RenderNode] descends before it renders a node's fallback (or nothing) and reports
 * [SduiTelemetry.onNodeBudgetExceeded]. Defaults to [MAX_UI_TREE_DEPTH]; hosts that render unusually
 * deep trees can raise it by providing a larger value, and hosts on tighter stacks can lower it.
 */
public val LocalMaxRenderDepth: ProvidableCompositionLocal<Int> =
    staticCompositionLocalOf { MAX_UI_TREE_DEPTH }

/**
 * Builds a [ScreenSource] for a route name nested inside a [dev.sdui.kmp.protocol.NavHost].
 *
 * Hosts wire this once at startup so widget renderers (specifically `widgets-nav`) can spin up
 * inner screens without depending on a transport directly. The factory is responsible for any
 * baseUrl / HttpClient wiring; the renderer just passes the route key from
 * [dev.sdui.kmp.protocol.NavHost.routes].
 */
public typealias NestedScreenSourceFactory = (route: String) -> ScreenSource

/**
 * Per-host factory that produces a [ScreenSource] for nested navigation destinations.
 *
 * Default `null` — when a `NavHost` renderer encounters a null factory it shows an inline
 * error placeholder rather than crashing. Hosts that ship navigation must provide one:
 *
 * ```kotlin
 * CompositionLocalProvider(
 *     LocalNestedScreenSourceFactory provides { route -> HttpScreenSource(client, baseUrl, "screens/$route") },
 * ) { SduiHost(...) }
 * ```
 *
 * Lives in `:runtime` because it is the seam between the renderer and the transport — neither
 * `widgets-nav` nor a specific transport module is the right home.
 */
public val LocalNestedScreenSourceFactory: ProvidableCompositionLocal<NestedScreenSourceFactory?> =
    staticCompositionLocalOf { null }
