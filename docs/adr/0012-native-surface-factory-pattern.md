# ADR 0012 — `NativeSurfaceFactory` is a kind-keyed, per-platform expect/actual class

**Status:** accepted (Phase P25, M-P25)

## Context

Server-driven UI has to ship a typed escape hatch for the rare-but-real cases where a host
needs a real platform widget — Google Maps, MKMapView, AVPlayer, ARKit. The protocol carries
the contract via [`NativeSurface`](../../protocol/src/commonMain/kotlin/dev/sdui/kmp/protocol/NativeSurface.kt)
(`kind: String`, `config: JsonObject`, `bindings: Map<String, StatePath>`,
`events: Map<String, List<Action>>`). Until M-P25 the protocol side and the registry / fallback
plumbing existed, but **no concrete factory shipped**. The first real factory (`sdui.map`,
introduced in `:widgets-native-map`) had to settle the conventions every future factory will
follow.

## Decision

A factory is:

1. A class that **implements `NativeSurfaceFactory`** with a constant `kind` (e.g. `"sdui.map"`)
   and a `handledVersions: ClosedRange<SchemaVersion>`.
2. **`expect class` in commonMain, `actual class` per target.** The shared expect declaration
   pins the public shape; per-target actuals pull in platform-specific dependencies (Google
   Maps Compose on Android, MapKit on iOS) without leaking them to common consumers.
3. **Decodes `NativeSurface.config` to a typed data class** via a `kotlinx-serialization`
   `@Serializable` `MapSurfaceConfig` (or equivalent). Decoding is a one-liner inside the
   factory's `Render`, gated by `runCatching { ... }.getOrNull()` so a malformed payload
   degrades to fallback rather than throwing.
4. **Reads `bindings` against `LocalStateStore`** to drive dynamic state (camera position,
   playback time, ...). Bindings keys are part of the wire contract; document them in the
   module README.
5. **Fires `events` through `LocalActionDispatcher`.** The factory writes the contextual key
   (e.g. `markerTapped.id`) into the local state store before dispatching so server-authored
   actions can branch on it.
6. **Never throws.** Decode failure, unsupported platform, missing API key — every path has a
   deterministic fallback (`MapSurfacePlaceholder` for `:widgets-native-map`).

## Graceful degradation

Two layers of fallback apply:

- **Protocol-level**, already documented in [ADR 0005](0005-native-surface-fallback.md):
  `NativeSurfaceNodeRenderer` looks up the factory in `LocalNativeSurfaceRegistry`; if no
  factory is registered for the kind (or its `handledVersions` excludes the node's `since`),
  the renderer emits the `NativeSurface.fallback` subtree.
- **Factory-level**, new in this ADR: an actual that has no native widget on its target
  (Compose for Desktop and Wasm have no first-class map) renders a Material 3 `Card`
  listing the marker titles. The host has registered the factory, so the protocol-level
  fallback never fires; the factory itself decides what "best-effort" looks like.

## API key handling discipline

Native widgets often need a vendor API key (Google Maps, Mapbox, ...). The pattern:

1. The module's `AndroidManifest.xml` ships a **placeholder string**
   (`REPLACE_WITH_YOUR_KEY`). It is committed to source.
2. Hosts override the manifest meta-data via `manifestPlaceholders` or by declaring the
   element themselves. Real keys never live in the repo.
3. The factory exposes `MapSurfaceFactory.instance(requireApiKey: Boolean)`. When `true`
   (default), the Composable checks the manifest meta-data at runtime and falls back to
   `MapSurfacePlaceholder` if the placeholder string is still in place. Hosts with a real
   key pass `false` to bypass the check.
4. The `MapSurfaceFactoryConfig` companion documents the placeholder string and the
   `<meta-data>` attribute name as constants so a host can write `assert(metadata == ...)`
   in its own startup wiring rather than copy-pasting strings.

## Consequences

- **Adding a new native kind = one module.** Author follows the recipe above; nothing else
  in the framework changes. The protocol stays untouched.
- **`expect class` adds compile-time per-target weight** but is the cleanest way to encode
  the platform-specific dependency graph. KMP type aliases would let common code refer to
  the platform type directly, but at the cost of leaking `MKMapView` into commonMain
  signatures during compilation.
- **Fallback discipline is testable.** `MapSurfaceConfigTest` exercises the decode path
  including the failure branch; the registry test (`MapSurfaceFactoryRobotTest`) confirms
  `kind` / `handledVersions` lookup.
- **The Android factory introduces the framework's first dependency on Google Play Services
  and the closed-source Maps SDK.** That is opt-in: hosts that don't want the dep don't
  register the factory; hosts that do already vend a Google Cloud account.
