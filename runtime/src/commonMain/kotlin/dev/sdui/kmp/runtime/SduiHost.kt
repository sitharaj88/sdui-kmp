package dev.sdui.kmp.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.Screen
import kotlinx.coroutines.flow.flowOf

/**
 * Renders whatever [ScreenState] the [source] currently emits.
 *
 * Wiring: the host provides a [WidgetRegistry] (populated by widget modules), an optional
 * [Navigator], [telemetry], [submitHandler], and [liveSource]. The [StateStore] and
 * [ActionDispatcher] are built per screen from [Screen.initialState].
 *
 * When [liveSource] is non-null the host subscribes to its events and applies them to the
 * rendered screen: [LiveEvent.StateUpdate] patches the store, [LiveEvent.TreePatchEvent]
 * rewrites the current screen tree via the tree-patch engine.
 */
@Composable
public fun SduiHost(
    source: ScreenSource,
    registry: WidgetRegistry,
    navigator: Navigator = NoOpNavigator,
    telemetry: SduiTelemetry = NoopTelemetry,
    submitHandler: SubmitHandler? = null,
    liveSource: LiveSource? = null,
    nativeSurfaceRegistry: NativeSurfaceRegistry = NativeSurfaceRegistry.Empty,
) {
    val state by source.screen.collectAsState(initial = ScreenState.Loading)
    when (val s = state) {
        ScreenState.Loading -> LoadingPlaceholder()
        is ScreenState.Error -> ErrorPlaceholder(s.error, onRetry = source::retry)
        is ScreenState.Ready -> RenderReady(
            screen = s.screen,
            registry = registry,
            navigator = navigator,
            telemetry = telemetry,
            submitHandler = submitHandler,
            liveSource = liveSource,
            nativeSurfaceRegistry = nativeSurfaceRegistry,
        )
    }
}

/**
 * Renders a single [Screen] against an explicit [StateStore]. Useful in previews and tests
 * that bypass transports.
 */
@Composable
public fun SduiHost(
    screen: Screen,
    registry: WidgetRegistry,
    store: StateStore = remember(screen) { StateStore(screen.initialState) },
    navigator: Navigator = NoOpNavigator,
    telemetry: SduiTelemetry = NoopTelemetry,
    submitHandler: SubmitHandler? = null,
    liveSource: LiveSource? = null,
    nativeSurfaceRegistry: NativeSurfaceRegistry = NativeSurfaceRegistry.Empty,
) {
    val dispatcher = remember(store, navigator, telemetry, submitHandler) {
        DefaultActionDispatcher(store, navigator, telemetry, submitHandler)
    }
    var currentScreen by remember(screen) { mutableStateOf(screen) }

    if (liveSource != null) {
        DisposableEffect(liveSource) {
            liveSource.start()
            onDispose { liveSource.stop() }
        }
        LaunchedEffect(liveSource, screen) {
            liveSource.events.collect { event ->
                when (event) {
                    is LiveEvent.StateUpdate -> store.patch(event.updates)
                    is LiveEvent.TreePatchEvent ->
                        currentScreen = currentScreen.apply(event.patch, telemetry = telemetry)
                    // A live-event kind added by a newer server that this client cannot decode is
                    // ignored rather than crashing the live stream.
                    is LiveEvent.Unknown -> Unit
                }
                telemetry.onNodeRendered("live_event", currentScreen.version)
            }
        }
    }

    CompositionLocalProvider(
        LocalRegistry provides registry,
        LocalStateStore provides store,
        LocalActionDispatcher provides dispatcher,
        LocalTelemetry provides telemetry,
        LocalNativeSurfaceRegistry provides nativeSurfaceRegistry,
    ) {
        RenderNode(currentScreen.root, Modifier.fillMaxSize())
    }
}

@Composable
private fun RenderReady(
    screen: Screen,
    registry: WidgetRegistry,
    navigator: Navigator,
    telemetry: SduiTelemetry,
    submitHandler: SubmitHandler?,
    liveSource: LiveSource?,
    nativeSurfaceRegistry: NativeSurfaceRegistry,
) {
    val store = remember(screen) { StateStore(screen.initialState) }
    SduiHost(
        screen = screen,
        registry = registry,
        store = store,
        navigator = navigator,
        telemetry = telemetry,
        submitHandler = submitHandler,
        liveSource = liveSource,
        nativeSurfaceRegistry = nativeSurfaceRegistry,
    )
}

@Composable
private fun LoadingPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Loading…")
    }
}

@Composable
private fun ErrorPlaceholder(error: Throwable, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Error: ${error.message ?: error::class.simpleName}")
    }
    // onRetry wired up in M2 via a proper error UI.
    @Suppress("UnusedExpression")
    onRetry
}

/** Adapter for hosts that want to feed a single [Screen] to the flow-based [SduiHost]. */
public fun staticScreenSource(screen: Screen): ScreenSource = object : ScreenSource {
    override val screen = flowOf(ScreenState.Ready(screen))
    override fun retry() {}
}
