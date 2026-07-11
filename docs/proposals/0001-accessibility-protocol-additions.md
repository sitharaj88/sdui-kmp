# Proposal 0001 — Additive accessibility fields for `:protocol`

**Status:** draft. This proposal does NOT modify `:protocol`. The diff is for the user to
review and accept; only after acceptance does anyone touch the sealed hierarchy.

## Why

The WCAG 2.2 AA conformance pass (M-P26) found four widgets where Compose's built-in
semantics + the existing `A11y` field can't fully express server intent. The fixes are
purely additive — every change preserves on-the-wire compatibility and existing renderers
keep working without re-deserialising state. Each gap is paired with the exact field to
add and a default value that keeps current behaviour bit-for-bit.

These fields would be added in a separate, deliberate PR after the user signs off on the
shape. None of them are required to ship the M-P26 audit work; they only unlock
finer-grained server control over assistive-tech behaviour.

## Gap 1 — `TextField.label`

### Current behaviour

```kotlin
@SerialName("text_field")
public data class TextField(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val path: StatePath,
    public val placeholder: Value<String>? = null,
    public val keyboard: Keyboard = Keyboard.Text,
    public val secure: Boolean = false,
    public val validation: Validation? = null,
    public val a11y: A11y? = null,
) : Leaf
```

The renderer emits an M3 `OutlinedTextField` with no label slot. Without an explicit
`a11y.label`, screen readers fall back to the placeholder — which WCAG 3.3.2 explicitly
calls out as insufficient (a placeholder disappears on focus, leaving the user without an
accessible name).

The current renderer mitigates this by projecting the resolved placeholder onto the
node's contentDescription when `a11y.label` is absent — strictly better than nothing, but
still a workaround.

### Proposed addition

```kotlin
@SerialName("text_field")
public data class TextField(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val path: StatePath,
    public val label: Value<String>? = null,        // NEW
    public val placeholder: Value<String>? = null,
    public val keyboard: Keyboard = Keyboard.Text,
    public val secure: Boolean = false,
    public val validation: Validation? = null,
    public val a11y: A11y? = null,
) : Leaf
```

`label: Value<String>? = null` is additive: the wire form gains an optional field with
default `null`, so older servers that do not set it still produce trees that decode
identically. The renderer would feed `label` into M3's `OutlinedTextField(label = ...)`
slot, which is the WCAG-compliant way to attach a persistent name.

### Migration path

1. Land the field with default `null`.
2. Update `TextFieldRenderer` to use `label` if present, else fall back to the current
   contentDescription-from-placeholder behaviour.
3. Add a server-side schema-linter rule that warns (not errors) when a `TextField` has
   no `label` and no `a11y.label`.

## Gap 2 — `Container.a11y.traversalIndex`

### Current behaviour

`Column`, `Row`, `Box`, and `LazyList` accept an `A11y?` field that maps to Compose
semantics, but the framework offers no way for the server to override the natural
traversal order. WCAG 2.4.3 (Focus Order) is satisfied by source order in 99 % of trees,
but server-driven UIs occasionally ship layouts where visual order and source order
diverge (RTL flips, overlaid bottom sheets, multi-column reading flows). Compose exposes
`SemanticsPropertyReceiver.traversalIndex: Float`; we have no way to feed it.

### Proposed addition

```kotlin
public data class A11y(
    public val label: Value<String>? = null,
    public val hint: Value<String>? = null,
    public val role: A11yRole? = null,
    public val liveRegion: LiveRegion = LiveRegion.Off,
    public val isHidden: Boolean = false,
    public val headingLevel: Int? = null,
    public val traversalIndex: Float? = null,        // NEW
)
```

`traversalIndex: Float? = null` is additive. `applyA11y` would emit
`if (a11y.traversalIndex != null) traversalIndex = a11y.traversalIndex` in the existing
`semantics { ... }` block.

### Migration path

1. Land the field with default `null`.
2. Extend `applyA11y` in one place; every widget that already accepts `a11y` benefits.
3. No renderer changes required.

## Gap 3 — `NavHost.routes` per-tab labels & icons

### Current behaviour

```kotlin
@SerialName("nav_host")
public data class NavHost(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val kind: NavKind,
    public val initial: Destination,
    public val routes: Map<String, String> = emptyMap(),
    public val a11y: A11y? = null,
) : Leaf
```

`TabNavRenderer` shows a Material 3 `NavigationBar` whose tab labels are the raw map
keys (`Text(tabId)`) and whose icons are placeholder coloured disks with no
contentDescription. Two a11y problems:

1. Tab labels are Kotlin identifiers (`profile`, `settings`), not human-readable strings.
   WCAG 2.4.6 (Headings and Labels) is borderline.
2. Icon-only tabs have no accessible name at all — the `NavigationBar` row reads "tab"
   with no label.

### Proposed addition

```kotlin
@Serializable
public data class NavRoute(
    public val route: String,
    public val label: Value<String>? = null,
    public val icon: IconToken? = null,
    public val a11yLabel: Value<String>? = null,
)

@Serializable
public enum class IconToken { Home, Search, Profile, Settings, Notifications, Cart, Library }

@SerialName("nav_host")
public data class NavHost(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val kind: NavKind,
    public val initial: Destination,
    public val routes: Map<String, String> = emptyMap(),
    public val routeMeta: Map<String, NavRoute> = emptyMap(),    // NEW
    public val a11y: A11y? = null,
) : Leaf
```

`routeMeta` is a new optional map. Older servers that emit only `routes` produce
identical trees. Newer servers can populate `routeMeta` with the same keys and gain
labelled, icon-bearing tabs. No `routes` value is repurposed.

### Migration path

1. Land `NavRoute` + `IconToken` enum + the `routeMeta` field with default `emptyMap()`.
2. `TabNavRenderer` reads `routeMeta[tabId]?.label?.resolve(store) ?: tabId` for the
   label, and similarly for `a11yLabel` and `icon`.
3. `IconToken` is a closed enum (additive-only by the same rule as every other enum); the
   renderer maps it to a Compose `Icons.*` constant.

## Gap 4 — `Image.decorative` flag

### Current behaviour

`Image.contentDescription` is `Value<String>?`. A null contentDescription is ambiguous:
"the server forgot" vs "this image is decorative and should be hidden from screen
readers". WCAG 1.1.1 differentiates these — decorative images need `role="presentation"`
or `aria-hidden`, not just a missing label.

### Proposed addition

```kotlin
@SerialName("image")
public data class Image(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val source: Value<String>,
    public val contentDescription: Value<String>? = null,
    public val contentScale: ContentScale = ContentScale.Fit,
    public val decorative: Boolean = false,        // NEW
    public val a11y: A11y? = null,
) : Leaf
```

(Apply identically to `AsyncImage`.)

`decorative: Boolean = false` is additive. The renderer's logic becomes:

* `decorative == true` → emit `Modifier.semantics { invisibleToUser() }` and ignore
  `contentDescription`.
* `decorative == false` and `contentDescription != null` → current behaviour.
* `decorative == false` and `contentDescription == null` → log a binding-error telemetry
  event so adopters can spot servers that forgot to make the call. (Behaviour-wise, falls
  through to current "no description" rendering — never throws.)

### Migration path

1. Land the field with default `false`. Older servers omit it; the renderer treats
   missing-description as "forgot, render without semantics" (current behaviour).
2. Existing trees that intentionally use null-description-as-decorative continue to work
   visually; the new field just gives them a way to be explicit.

## Non-additions considered and rejected

- **Custom ARIA attributes as a free-form map.** Encourages clients to drift apart on
  vendor-specific keys. Punt to a future ADR if a real need surfaces.
- **`Button.accessibleHint` / `accessibleLabel` separate from `A11y.label`.** `A11y` is
  the universal hook; doubling up on the button is fragmentation.
- **`LazyList.collectionInfo`.** Compose's lazy lists already expose collection semantics
  automatically; no protocol surface needed.

## Decision needed

Each gap above is independent. The user should accept or reject one-by-one. None of them
are required to satisfy WCAG 2.2 AA on the *current* sample apps — they raise the ceiling
on what an adopter can express, not the floor of what the framework guarantees.
