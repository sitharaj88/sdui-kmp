# Architecture

The canonical technical reference. Every design decision in the codebase should trace back to something in this document. If it does not, either the document is incomplete or the decision is wrong.

## Module topology

```
sdui-kmp/
├── protocol/                  # Tier 1. The crown jewel.
├── runtime/                   # Tier 2. Client-side rendering core.
├── server/                    # Tier 2. Server-side DSL.
├── widgets-core/              # Tier 3. Column, Row, Text, Button, LazyList.
├── widgets-forms/             # Tier 3. TextField, Checkbox, Picker, validation.
├── widgets-media/             # Tier 3. Image, AsyncImage.
├── transport-http/            # Tier 3. Ktor-based HTTP screen source.
├── transport-live/            # Tier 3. WebSocket/SSE live source.
├── tooling-cli/               # Tier 0. Schema linter, snapshot generator.
├── tooling-preview/           # Tier 0. Compose MP preview harness app.
├── tooling-testing/           # Tier 0. Golden snapshot utilities.
├── tooling-telemetry/         # Tier 0. Telemetry hook interfaces.
└── samples/
    ├── sample-android/
    ├── sample-ios/
    ├── sample-desktop/
    ├── sample-wasm/
    └── sample-server/         # Ktor server emitting trees to all clients.
```

**Dependency rules (enforced by a Gradle convention plugin):**

- `protocol` depends on nothing except `kotlinx.serialization`.
- `runtime` and `server` both depend on `protocol`. They never depend on each other.
- Widget modules depend on `runtime` only. Never on each other.
- Transport modules depend on `runtime` and their platform client (Ktor, etc).
- Sample apps depend on whatever they need from Tier 3 and below.

## Protocol module — the crown jewel

### Core types

```kotlin
@JvmInline @Serializable
value class NodeId(val value: String)

@JvmInline @Serializable
value class StatePath(val value: String) {
    fun child(segment: String) = StatePath("$value.$segment")
    companion object { val Root = StatePath("") }
}

@JvmInline @Serializable
value class SchemaVersion(val value: Int) : Comparable<SchemaVersion> {
    override fun compareTo(other: SchemaVersion) = value.compareTo(other.value)
    companion object { val V1 = SchemaVersion(1) }
}

@JvmInline @Serializable
value class ScreenId(val value: String)
```

### Node hierarchy

```kotlin
@Serializable
sealed interface UiNode {
    val id: NodeId
    val since: SchemaVersion
    val fallback: UiNode?
}

@Serializable
sealed interface Container : UiNode {
    val children: List<UiNode>
}

@Serializable
sealed interface Leaf : UiNode
```

Every concrete node extends `Container` or `Leaf`. The `@SerialName` discriminator is the node type string. A closed polymorphic hierarchy via `kotlinx.serialization`'s sealed classes gives us exhaustive `when` branches in the renderer — adding a new node type is a compile error in every renderer until handled.

### Bindings

```kotlin
@Serializable
sealed interface Value<out T> {
    @Serializable @SerialName("literal")
    data class Literal<T>(val value: T) : Value<T>

    @Serializable @SerialName("bind")
    data class Bind<T>(val path: StatePath) : Value<T>

    @Serializable @SerialName("template")
    data class Template(val pattern: String, val bindings: Map<String, StatePath>) : Value<String>
}
```

`Value<T>` is the only way to express a field that may be static or state-bound. **No raw strings anywhere in widget fields for anything that could be dynamic.**

### Actions

```kotlin
@Serializable
sealed interface Action {
    @Serializable @SerialName("navigate")
    data class Navigate(val destination: Destination, val replace: Boolean = false) : Action

    @Serializable @SerialName("update_state")
    data class UpdateState(val path: StatePath, val value: Value<JsonElement>) : Action

    @Serializable @SerialName("submit")
    data class Submit(
        val endpoint: String,
        val method: HttpMethod = HttpMethod.Post,
        val payload: Map<String, StatePath> = emptyMap(),
        val policy: ActionPolicy = ActionPolicy(),
        val onSuccess: List<Action> = emptyList(),
        val onError: List<Action> = emptyList(),
    ) : Action

    @Serializable @SerialName("sequence")
    data class Sequence(val actions: List<Action>) : Action

    @Serializable @SerialName("when")
    data class When(
        val condition: Predicate,
        val then: List<Action>,
        val otherwise: List<Action> = emptyList(),
    ) : Action
}

@Serializable
data class ActionPolicy(
    val execution: Execution = Execution.Online,
    val optimistic: OptimisticUpdate? = null,
    val retry: RetryPolicy = RetryPolicy.None,
    val idempotencyKey: StatePath? = null,
)

@Serializable enum class Execution { Online, OfflineQueue, LocalOnly }

@Serializable
data class OptimisticUpdate(
    val stateUpdates: Map<StatePath, Value<JsonElement>>,
    val rollbackOnError: Boolean = true,
)

@Serializable
sealed interface RetryPolicy {
    @Serializable @SerialName("none") object None : RetryPolicy
    @Serializable @SerialName("exponential")
    data class Exponential(val maxAttempts: Int = 3, val initialDelayMs: Long = 500) : RetryPolicy
}
```

### Predicates

```kotlin
@Serializable
sealed interface Predicate {
    @Serializable @SerialName("eq") data class Eq(val path: StatePath, val value: JsonElement) : Predicate
    @Serializable @SerialName("not") data class Not(val inner: Predicate) : Predicate
    @Serializable @SerialName("empty") data class IsEmpty(val path: StatePath) : Predicate
    @Serializable @SerialName("all") data class All(val predicates: List<Predicate>) : Predicate
    @Serializable @SerialName("any") data class Any(val predicates: List<Predicate>) : Predicate
}
```

**Deliberately small.** No arithmetic. No string ops. No regex. If the server needs it, the server does it.

### State scopes

```kotlin
@Serializable enum class StateScope { Global, Screen, Node, Ephemeral }

@Serializable
data class StateDeclaration(
    val path: StatePath,
    val scope: StateScope,
    val initial: JsonElement,
    val persist: Boolean = false,
)
```

Each screen declares its state contract upfront. Scopes cascade outward: node lookup falls through to screen, then global. Persistent state survives process death via the platform's preferences/state-save mechanism.

### Navigation

```kotlin
@Serializable
sealed interface Destination {
    @Serializable @SerialName("screen")
    data class ScreenDest(val route: String, val args: JsonObject = JsonObject(emptyMap())) : Destination

    @Serializable @SerialName("modal")
    data class Modal(val route: String, val args: JsonObject = JsonObject(emptyMap())) : Destination

    @Serializable @SerialName("tab")
    data class TabSwitch(val tabId: String) : Destination

    @Serializable @SerialName("back")
    data class Back(val count: Int = 1) : Destination

    @Serializable @SerialName("pop_to_root") object PopToRoot : Destination
}
```

### Design tokens

```kotlin
@Serializable
sealed interface ColorToken {
    @Serializable @SerialName("surface") object Surface : ColorToken
    @Serializable @SerialName("on_surface") object OnSurface : ColorToken
    @Serializable @SerialName("primary") object Primary : ColorToken
    @Serializable @SerialName("on_primary") object OnPrimary : ColorToken
    @Serializable @SerialName("error") object Error : ColorToken
    @Serializable @SerialName("warning") object Warning : ColorToken
    @Serializable @SerialName("success") object Success : ColorToken
    @Serializable @SerialName("muted") object Muted : ColorToken
}

@Serializable enum class Spacing { None, Xs, Sm, Md, Lg, Xl, Xxl }

@Serializable enum class TextStyleToken {
    Display, Heading, Title, Body, BodySmall, Caption, Label, Error
}

@Serializable enum class RadiusToken { None, Sm, Md, Lg, Full }

@Serializable enum class ElevationToken { None, Sm, Md, Lg }

@Serializable
sealed interface IconToken {
    @Serializable @SerialName("named")
    data class Named(val name: String) : IconToken   // "add", "close", "chevron_right"
}
```

**No hex colors, no pixel sizes, no font names cross the wire.** Ever.

### Accessibility (mandatory on base type)

```kotlin
@Serializable
data class A11y(
    val label: Value<String>? = null,
    val hint: Value<String>? = null,
    val role: A11yRole? = null,
    val liveRegion: LiveRegion = LiveRegion.Off,
    val isHidden: Boolean = false,
    val headingLevel: Int? = null,
)

@Serializable enum class A11yRole { Button, Link, Image, Header, List, ListItem, Checkbox, Radio, Switch, Slider, TextField }

@Serializable enum class LiveRegion { Off, Polite, Assertive }
```

Every node accepts an optional `a11y: A11y?`. We add this to the base type **before v1 ships**. Retrofitting accessibility into a live protocol is a major version bump.

### Screen envelope

```kotlin
@Serializable
data class Screen(
    val id: ScreenId,
    val version: SchemaVersion,
    val root: UiNode,
    val stateDeclarations: List<StateDeclaration> = emptyList(),
    val initialState: Map<StatePath, JsonElement> = emptyMap(),
    val metadata: ScreenMetadata = ScreenMetadata(),
)

@Serializable
data class ScreenMetadata(
    val title: Value<String>? = null,
    val analyticsName: String? = null,
    val cacheTtlSeconds: Long? = null,
)
```

## Runtime module

### The rendering loop

```kotlin
@Composable
fun SduiHost(
    source: ScreenSource,
    registry: WidgetRegistry,
    actionHandler: ActionHandler,
    navigator: Navigator,
    telemetry: SduiTelemetry = NoopTelemetry,
) {
    val screenState by source.screen.collectAsState()
    when (val s = screenState) {
        is ScreenState.Loading -> LoadingPlaceholder()
        is ScreenState.Error   -> ErrorPlaceholder(s.error, onRetry = source::retry)
        is ScreenState.Ready   -> RenderReady(s.screen, registry, actionHandler, navigator, telemetry)
    }
}

@Composable
fun RenderNode(node: UiNode, modifier: Modifier = Modifier) {
    val registry = LocalRegistry.current
    val renderer = registry.rendererFor(node)
    if (renderer != null) {
        renderer.Render(node, modifier)
    } else {
        LocalTelemetry.current.onUnknownNode(node::class.simpleName.orEmpty(), emptyList())
        node.fallback?.let { RenderNode(it, modifier) }
    }
}
```

The `UnknownNodeFallback` path is the single most important line in the runtime. It is covered by a test that cannot be deleted.

### State store — scoped tree

```kotlin
@Stable
class StateStore(
    initial: Map<StatePath, JsonElement> = emptyMap(),
    private val parent: StateStore? = null,
    private val scope: StateScope = StateScope.Global,
) {
    private val local = mutableStateOf(initial.toPersistentMap())

    fun read(path: StatePath): JsonElement? =
        local.value[path] ?: parent?.read(path)

    fun update(path: StatePath, value: JsonElement) {
        local.value = local.value.put(path, value)
    }

    fun patch(updates: Map<StatePath, JsonElement>) {
        local.value = local.value.putAll(updates)
    }

    fun child(scope: StateScope, initial: Map<StatePath, JsonElement> = emptyMap()): StateStore =
        StateStore(initial, parent = this, scope = scope)
}
```

Writes go to the local scope. Reads fall through to parents. This gives us node-scoped state for list items without global collisions.

### Widget registry

```kotlin
interface NodeRenderer<T : UiNode> {
    val nodeClass: KClass<T>
    val handledVersions: ClosedRange<SchemaVersion>
    @Composable fun Render(node: T, modifier: Modifier)
}

class WidgetRegistry private constructor(
    private val renderers: Map<KClass<out UiNode>, NodeRenderer<*>>,
    private val clientVersion: SchemaVersion,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : UiNode> rendererFor(node: T): NodeRenderer<T>? {
        val r = renderers[node::class] as? NodeRenderer<T> ?: return null
        return if (clientVersion in r.handledVersions) r else null
    }

    class Builder(private val clientVersion: SchemaVersion) {
        private val renderers = mutableMapOf<KClass<out UiNode>, NodeRenderer<*>>()
        fun <T : UiNode> register(renderer: NodeRenderer<T>) = apply {
            renderers[renderer.nodeClass] = renderer
        }
        fun build() = WidgetRegistry(renderers.toMap(), clientVersion)
    }
}
```

Widgets register per module. No monolithic `when`. No god file.

### Action dispatcher

```kotlin
interface ActionHandler {
    suspend fun dispatch(action: Action, ctx: ActionContext): ActionResult
}

data class ActionContext(
    val store: StateStore,
    val navigator: Navigator,
    val screenId: ScreenId,
    val nodeId: NodeId,
)

sealed interface ActionResult {
    data object Success : ActionResult
    data class Failure(val error: Throwable) : ActionResult
    data class Patch(val updates: Map<StatePath, JsonElement>, val tree: TreePatch? = null) : ActionResult
    data class Conflict(val serverState: Map<StatePath, JsonElement>) : ActionResult
}
```

The default implementation handles: action sequence unrolling, predicate evaluation, optimistic updates with rollback, offline queue with idempotency keys, retry with exponential backoff.

### Native surfaces

```kotlin
@Serializable
@SerialName("native")
data class NativeSurface(
    override val id: NodeId,
    override val since: SchemaVersion,
    override val fallback: UiNode?,
    val kind: String,                              // "map", "camera", "player", "biometric"
    val config: JsonObject,
    val bindings: Map<String, StatePath> = emptyMap(),
    val events: Map<String, List<Action>> = emptyMap(),
    val a11y: A11y? = null,
) : Leaf

interface NativeSurfaceFactory {
    val kind: String
    val handledVersions: ClosedRange<SchemaVersion>
    @Composable fun Render(surface: NativeSurface, modifier: Modifier)
}
```

Factories are registered per platform. `kind` strings are namespaced (`sdui.map`, `sdui.camera`) to avoid collisions with host-app additions.

### LazyList (the most important widget)

```kotlin
@Serializable
@SerialName("lazy_list")
data class LazyList(
    override val id: NodeId,
    override val since: SchemaVersion,
    override val fallback: UiNode?,
    val source: ListSource,
    val itemTemplate: UiNode,
    val itemKeyPath: StatePath,
    val orientation: Orientation = Orientation.Vertical,
    val spacing: Spacing = Spacing.None,
    val padding: EdgeInsets = EdgeInsets.Zero,
    val emptyState: UiNode? = null,
    val loadingState: UiNode? = null,
    val errorState: UiNode? = null,
    val pullToRefresh: Boolean = false,
    val a11y: A11y? = null,
) : Leaf

@Serializable
sealed interface ListSource {
    @Serializable @SerialName("inline")
    data class Inline(val items: List<JsonObject>) : ListSource

    @Serializable @SerialName("paged")
    data class Paged(val endpoint: String, val pageSize: Int = 20, val cursor: String? = null) : ListSource

    @Serializable @SerialName("bound")
    data class Bound(val path: StatePath) : ListSource
}
```

Inside the template, bindings resolve against the current item. The renderer creates a Node-scoped `StateStore` per visible item so per-item state (expanded, liked, selected) does not collide.

## Server module — DSL

```kotlin
fun screen(id: String, version: SchemaVersion = SchemaVersion.V1, block: ScreenScope.() -> Unit): Screen

class ScreenScope {
    fun column(spacing: Spacing = Spacing.None, padding: EdgeInsets = EdgeInsets.Zero, block: ScreenScope.() -> Unit)
    fun row(spacing: Spacing = Spacing.None, padding: EdgeInsets = EdgeInsets.Zero, block: ScreenScope.() -> Unit)
    fun text(content: String, style: TextStyleToken = TextStyleToken.Body, color: ColorToken? = null, a11y: A11y? = null)
    fun text(content: Value<String>, style: TextStyleToken = TextStyleToken.Body, color: ColorToken? = null, a11y: A11y? = null)
    fun button(label: Value<String>, action: Action, style: ButtonStyle = ButtonStyle.Primary, a11y: A11y? = null)
    fun textField(path: StatePath, placeholder: Value<String>? = null, keyboard: Keyboard = Keyboard.Text, secure: Boolean = false, validation: Validation? = null)
    fun lazyList(source: ListSource, itemKeyPath: StatePath, block: ScreenScope.() -> Unit)
    fun nativeSurface(kind: String, config: JsonObject, bindings: Map<String, StatePath> = emptyMap(), events: Map<String, List<Action>> = emptyMap())
    fun navHost(kind: NavKind, initial: Destination, routes: Map<String, String>, block: ScreenScope.() -> Unit)

    fun binding(path: String): Value<String> = Value.Bind(StatePath(path))
    fun template(pattern: String, vararg bindings: Pair<String, String>): Value<String> =
        Value.Template(pattern, bindings.associate { it.first to StatePath(it.second) })
}
```

Node ids are assigned automatically by the DSL using a deterministic hash of call-site plus a counter, so identical DSL produces identical ids across server restarts — essential for state survival across re-renders.

## Transport layer

### HTTP source

```
GET /screens/{route}
Accept: application/vnd.sdui+json; version=3
If-None-Match: "etag-value"
```

Responses:

- `200` with body = full `Screen` JSON, `ETag` header for caching.
- `304` = cached screen still valid.
- `4xx/5xx` = `ScreenState.Error` with structured error body.

### Live source (WebSocket)

```
ws://host/live/{screenId}
```

Messages are `LiveEvent` JSON. Client subscribes by sending `{"type":"subscribe","topic":"..."}`. Server pushes `StateUpdate`, `TreePatch`, `Append`, `Remove`, `Replace` events.

### Tree patches

RFC 6902 JSON Patch, applied by node id (not JSON pointer). Client maintains a `Map<NodeId, UiNode>` index for O(1) node lookup during patch application.

```kotlin
@Serializable
data class TreePatch(val ops: List<PatchOp>)

@Serializable
sealed interface PatchOp {
    @Serializable @SerialName("replace")
    data class Replace(val nodeId: NodeId, val node: UiNode) : PatchOp

    @Serializable @SerialName("append")
    data class Append(val parentId: NodeId, val nodes: List<UiNode>) : PatchOp

    @Serializable @SerialName("remove")
    data class Remove(val nodeIds: List<NodeId>) : PatchOp
}
```

## Tooling

### Schema linter

Runs on every PR. Compares `protocol-snapshot.json` (committed at each release) against current `:protocol` Kotlin sources. Fails CI on:

- Removed node type.
- Removed field.
- Field type changed.
- Nullability tightened (nullable → non-nullable).
- Enum case removed.
- `@SerialName` discriminator changed.

Allowed (no failure):

- Added node type.
- Added field with default.
- Added enum case.
- Added `@Deprecated` annotation.

### Preview harness

Compose Multiplatform desktop app. File-watches a directory of `*.sdui.json` trees. Renders the tree live in a split pane: JSON on the left, rendered output on the right. Designers use this to iterate without a server.

### Golden snapshot tests

Each widget has a `screenshots/` directory with `widget_name_state.png` files. Paparazzi on Android, Roborazzi everywhere else. A PR changing pixel output must update the golden or explain why in the PR description.

### Telemetry hooks

```kotlin
interface SduiTelemetry {
    fun onScreenRendered(id: ScreenId, version: SchemaVersion, durationMs: Long)
    fun onNodeRendered(type: String, version: SchemaVersion)
    fun onUnknownNode(type: String, trace: List<NodeId>)
    fun onActionDispatched(action: Action, result: ActionResult, durationMs: Long)
    fun onBindingError(path: StatePath, expected: String, got: String)
    fun onPatchApplied(screenId: ScreenId, ops: Int)
    fun onLiveEventReceived(event: String)
}
```

The framework ships a `NoopTelemetry` default. Host apps wire these to their analytics.

## The ten invariants

These are the properties the test suite enforces. If any of these fails, the build is broken.

1. A `Screen` survives round-trip serialization with identical byte output.
2. A client at `SchemaVersion(N)` renders a tree containing a `SchemaVersion(N+1)` node by falling back, never by throwing.
3. An unknown discriminator in the JSON decodes to a sentinel node that renders the fallback.
4. Two identical DSL calls on the same server produce identical `NodeId`s.
5. A `LazyList` of 10,000 items scrolls at 60 fps on a mid-tier Android device (release build, R8 enabled).
6. An offline `Submit` with an idempotency key is retried exactly once after reconnection.
7. An optimistic update with `rollbackOnError = true` fully reverts on server error.
8. A tree patch applied to a `Screen` produces the same final tree as a fresh fetch.
9. Removing any public type from `:protocol` fails the schema linter.
10. Every widget has at least one golden snapshot test.
