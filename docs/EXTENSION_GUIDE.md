# Extension guide

`sdui-kmp` exposes four explicit extension seams. Use them — don't fork the framework.

| Seam | Interface | Default | Module |
|---|---|---|---|
| Custom widgets | `NodeRenderer<T>` | none — you ship the renderer | `:runtime` |
| Native surfaces | `NativeSurfaceFactory` | none — falls back to the protocol's `fallback` tree | `:runtime` |
| Telemetry | `SduiTelemetry` | `NoopTelemetry` (silent) | `:runtime` |
| Image loading | `ImageLoader` | `PlaceholderImageLoader` (renders a labeled box) | `:widgets-media` |

Plus two protocol-side extensions:
- New action types: subclass `Action` (sealed); register a handler in your `ActionDispatcher`
- New transport: implement `ScreenSource` and/or `LiveSource`

This guide walks each.

## Adding a custom widget

A widget needs three pieces: a protocol type, a renderer, and a registry registration.

### 1. Protocol type

Add a `@Serializable` `data class` extending `Leaf` or `Container`. Pick a stable
`@SerialName`. Every field must have a default for additive evolution.

```kotlin
// In your own :my-widgets protocol module, or in a fork's protocol module
@Serializable
@SerialName("rating")
data class Rating(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    val stars: Value<Int>,                        // 0..5
    val onChange: Action? = null,
    val a11y: A11y? = null,
) : Leaf
```

If you're extending the canonical protocol, run `./gradlew captureProtocolSnapshot` to update
the baseline. The schema linter (`./gradlew verifyProtocolSnapshot`) will then accept the
addition and reject any subsequent breaking change.

### 2. Renderer

Implement `NodeRenderer<T>`:

```kotlin
object RatingRenderer : NodeRenderer<Rating> {
    override val nodeClass = Rating::class
    override val handledVersions = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: Rating, modifier: Modifier) {
        val store = LocalStateStore.current
        val dispatcher = LocalActionDispatcher.current
        val scope = rememberCoroutineScope()
        val current = (node.stars as? Value.Literal)?.value?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        Row(modifier = modifier.applyA11y(node.a11y, store)) {
            repeat(5) { i ->
                val filled = i < current
                IconButton(onClick = {
                    node.onChange?.let { action ->
                        scope.launch { dispatcher.dispatch(action) }
                    }
                }) {
                    Icon(if (filled) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null)
                }
            }
        }
    }
}
```

### 3. Registration

```kotlin
val registry = WidgetRegistry.build {
    WidgetsCore.register(this)
    register(RatingRenderer)        // add yours alongside the built-ins
}
```

## Adding a native surface

Native surfaces are the typed escape hatch — they wrap platform UI (Google Maps, ExoPlayer,
biometric prompts, …) inside the protocol so servers can describe them and clients can
fall back gracefully when a factory isn't registered.

### Factory

```kotlin
class GoogleMapsFactory : NativeSurfaceFactory {
    override val kind = "sdui.map"
    override val handledVersions = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(surface: NativeSurface, modifier: Modifier) {
        val centerLat = surface.config["center_lat"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val centerLng = surface.config["center_lng"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val zoom = surface.config["zoom"]?.jsonPrimitive?.intOrNull ?: 13

        // Platform-specific: AndroidView { GoogleMapsView(it) } on Android,
        //                   UIKitView { MKMapView(...) } on iOS, etc.
    }
}
```

### Registration

```kotlin
val nativeRegistry = NativeSurfaceRegistry.build {
    register(GoogleMapsFactory())
}
SduiHost(
    screen = screen,
    registry = widgetRegistry,
    nativeSurfaceRegistry = nativeRegistry,
)
```

A client that doesn't register a factory for `sdui.map` doesn't crash — `NativeSurfaceNodeRenderer`
fires `telemetry.onUnknownNode("native:sdui.map", ...)` and renders the node's
`fallback` tree (or nothing). See [ADR-0005](adr/0005-native-surface-fallback.md).

## Wiring telemetry

`SduiTelemetry` has hooks for every event the runtime cares about: screen rendered, node
rendered, unknown node, action dispatched, binding error.

### Custom implementation

```kotlin
class FirebaseTelemetry : SduiTelemetry {
    override fun onScreenRendered(id: ScreenId, version: SchemaVersion, durationMs: Long) {
        FirebaseAnalytics.getInstance(context).logEvent("sdui_screen_rendered", bundleOf(
            "screen_id" to id.value,
            "version" to version.value,
            "duration_ms" to durationMs,
        ))
    }
    override fun onUnknownNode(type: String, trace: List<NodeId>) {
        FirebaseCrashlytics.getInstance().recordException(
            IllegalStateException("Unknown node $type at ${trace.joinToString("/") { it.value }}")
        )
    }
    // ... onNodeRendered, onActionDispatched, onBindingError
}
```

### Test double

For unit tests, use `RecordingTelemetry` from `:tooling-telemetry`:

```kotlin
val telemetry = RecordingTelemetry()
SduiHost(screen = screen, registry = registry, telemetry = telemetry)

assertEquals(1, telemetry.unknownNode.size)
assertEquals("rating", telemetry.unknownNode[0].type)
```

## Wiring an image loader

The default `PlaceholderImageLoader` renders a labeled box — useful for previews and
testing, useless in production. Plug in a real loader via `LocalImageLoader`.

### With Coil 3 (multiplatform)

```kotlin
class Coil3ImageLoader : ImageLoader {
    @Composable
    override fun Image(source: String, contentDescription: String?, contentScale: ContentScale, modifier: Modifier) {
        coil3.compose.AsyncImage(
            model = source,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale.toCompose(),
        )
    }
    @Composable
    override fun AsyncImage(url: String, contentDescription: String?, contentScale: ContentScale,
                           placeholder: (@Composable (Modifier) -> Unit)?, error: (@Composable (Modifier) -> Unit)?,
                           modifier: Modifier) {
        coil3.compose.SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = modifier,
            loading = { placeholder?.invoke(Modifier) ?: CircularProgressIndicator() },
            error = { error?.invoke(Modifier) ?: Icon(Icons.Default.BrokenImage, null) },
            contentScale = contentScale.toCompose(),
        )
    }
}
```

### Provide it via composition local

```kotlin
CompositionLocalProvider(LocalImageLoader provides Coil3ImageLoader()) {
    SduiHost(screen = screen, registry = registry)
}
```

Every `Image` and `AsyncImage` in the tree now uses Coil. No widget-side change.

## Adding a custom action

Actions are data, not lambdas — the protocol's sealed `Action` hierarchy is closed by
design. To add one:

1. Add the variant to `:protocol`'s `Action.kt`. Run `captureProtocolSnapshot`.
2. Update `DefaultActionDispatcher` to handle it (the `when (action)` is exhaustive — the
   compiler will tell you what's missing).
3. If your action needs IO (HTTP, IPC, FFI), thread the dependency in via the dispatcher's
   constructor and document the seam (see `SubmitHandler` for the pattern).

If you can't fork the protocol, model your action via existing primitives:

- One-shot HTTP call → `Action.Submit`
- Local state mutation → `Action.UpdateState`
- Conditional → `Action.When`
- Multiple steps → `Action.Sequence`

That covers most use cases without protocol changes.

## Custom transport

`ScreenSource` is the seam for fetching screens; `LiveSource` is for push events. Both are
plain Kotlin interfaces in `:runtime/commonMain`.

### Example: gRPC transport

```kotlin
class GrpcScreenSource(
    private val stub: ScreensServiceGrpcKt.ScreensServiceCoroutineStub,
    private val route: String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ScreenSource {
    private val _state = MutableStateFlow<ScreenState>(ScreenState.Loading)
    override val screen: StateFlow<ScreenState> = _state.asStateFlow()
    override fun retry() { fetch() }

    init { fetch() }
    private fun fetch() {
        scope.launch {
            try {
                val proto = stub.fetchScreen(FetchRequest(route = route))
                _state.value = ScreenState.Ready(proto.toSduiScreen())   // your bridge
            } catch (e: Throwable) {
                _state.value = ScreenState.Error(e)
            }
        }
    }
}
```

Pass it to `SduiHost(source = grpcSource, ...)` in place of `HttpScreenSource`. The runtime
doesn't care what wire protocol you use — only that it produces `ScreenState` values.

## Where extensions live

| What you're extending | Where it goes | Why |
|---|---|---|
| New widget | Your own `:my-widgets` module depending on `:runtime` | Keeps third-party widgets out of the framework's release surface |
| Native surface factory | Your platform module (Android-only / iOS-only feature) | Factories often need platform APIs unavailable in commonMain |
| Telemetry impl | Your app or analytics-adapter module | Wires to your concrete observability stack |
| Image loader | Your app or `:my-images` module | Avoids forcing every consumer to take a Coil dep |
| Transport | New `:transport-foo` module | Mirrors the existing `:transport-http` / `:transport-live` split |

The framework's `:runtime` and `:protocol` should never need to change for an extension to
work. If you find yourself wanting to modify them, you've found a missing seam — open an
issue with the use case.
