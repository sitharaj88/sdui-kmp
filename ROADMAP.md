# Roadmap

Ten milestones from empty repo to production-grade framework. Each milestone is independently shippable and produces a demo that can be run.

The purpose of the milestones is to **defer complexity, not hide it**. We build the smallest useful slice end-to-end, then widen it, then widen it again. If anything is wrong in the protocol, we find out in M1, not M7.

## M0 — Bootstrap (week 1)

**Goal:** An empty multi-module KMP project that builds on all five targets and runs on CI.

- Gradle 8.x, Kotlin 2.x, Compose Multiplatform.
- Version catalog in `gradle/libs.versions.toml`.
- Convention plugins: `sdui.kmp.library`, `sdui.kmp.module`, `sdui.jvm.library`, `sdui.compose.library`.
- Dependency rule enforcement via Gradle configuration.
- GitHub Actions CI: build on Linux (JVM, Android, Wasm), macOS (iOS, Desktop).
- `:protocol` module exists and produces an empty artifact.
- Sample app shells exist and run on all five platforms (display "Hello, sdui-kmp").

**Demo:** CI green on all platforms. Empty Android app launches. Empty desktop app launches.

## M1 — Protocol v0 and the smallest renderer (weeks 2–3)

**Goal:** A server emits a `Column { Text("hello") }`; an Android client renders it.

- `:protocol` — `UiNode`, `Container`, `Leaf`, `NodeId`, `StatePath`, `SchemaVersion`, `Screen`.
- Exactly three widgets defined: `Column`, `Text`, `Button`.
- `Value<T>` with Literal and Bind only (no Template yet).
- `Action.Navigate` and `Action.UpdateState` only.
- `ColorToken`, `Spacing`, `TextStyleToken` enums.
- `A11y` type defined and present on all three widgets.
- `:runtime` — `SduiHost`, `WidgetRegistry`, basic `StateStore` (flat, scopes later), `RenderNode` with fallback path.
- `:widgets-core` — renderers for the three widgets.
- `:server` — minimal DSL with `screen {}`, `column {}`, `text()`, `button()`.
- `:transport-http` — Ktor-based `HttpScreenSource`, no caching yet.
- `:sample-server` — serves one hardcoded screen.
- `:sample-android` — connects to `:sample-server` and renders.

**Demo:** Run the server, launch the Android app, see a column of text with a button that navigates.

## M2 — All platforms rendering (week 4)

**Goal:** The M1 demo runs on iOS, Desktop, and Wasm, not just Android.

- Platform-specific `SduiHost` entry points.
- Ktor engines per platform (Darwin for iOS, Java for Desktop, Js for Wasm).
- Navigation integration: Jetpack Nav on Android, Voyager or Decompose in commonMain for cross-platform.
- Golden snapshot tests for the three widgets on all platforms.

**Demo:** Same server, same screen, rendering identically on five platforms.

## M3 — The state system (weeks 5–6)

**Goal:** Scoped state, bindings, and a working form.

- `StateScope` enum and scoped `StateStore` tree.
- `StateDeclaration` and initialization.
- `Value.Template` for interpolation.
- `Predicate` hierarchy and evaluation.
- `:widgets-forms` — `TextField`, `Checkbox` with live binding.
- `Action.UpdateState`, `Action.Sequence`, `Action.When`.
- `Action.Submit` with synchronous success/error handling (no optimistic yet).
- Client-side validation via `Validation` type.

**Demo:** Server-driven login screen. Typing updates state. Submit posts to server. Error updates UI.

## M4 — LazyList and navigation (weeks 7–8)

**Goal:** Feeds and multi-screen apps.

- `LazyList` widget with `Inline`, `Paged`, and `Bound` sources.
- Per-item state scoping.
- `Pagination` and cursor handling.
- `NavHost` widget with Stack, Tab, BottomSheet kinds.
- `Destination` hierarchy and navigator integration.
- Deep link parsing.
- Pull-to-refresh on `LazyList`.

**Demo:** A three-tab app (Feed, Search, Profile) where each tab is a server-driven screen with paginated content.

## M5 — Transport hardening (weeks 9–10)

**Goal:** Caching, deltas, offline.

- ETag-based HTTP caching.
- Disk cache for screens (multiplatform).
- `TreePatch` application engine.
- `:transport-live` — WebSocket client, `LiveSource` interface.
- `LiveEvent` handling in `SduiHost`.
- Offline action queue with idempotency keys.
- `ActionPolicy.OfflineQueue` with retry.
- Optimistic updates with rollback.

**Demo:** A chat app with realtime message arrival and optimistic sends that survive airplane mode.

## M6 — Native surfaces (weeks 11–12)

**Goal:** Escape hatches that do not compromise the protocol.

- `NativeSurface` node and `NativeSurfaceFactory` interface.
- Reference implementations for: `sdui.map` (Google Maps on Android, Apple Maps on iOS), `sdui.player` (ExoPlayer/AVPlayer), `sdui.biometric`.
- Typed config schemas per kind, linted separately.
- `:widgets-media` — `Image`, `AsyncImage`.

**Demo:** A delivery-tracking screen with a live map, driver marker, and ETA text.

## M7 — Tooling (weeks 13–14)

**Goal:** The framework is usable by someone who did not write it.

- `:tooling-cli` — schema linter running in CI.
- Protocol snapshot generation on release tags.
- `:tooling-preview` — file-watching Compose desktop app.
- Golden snapshot test infrastructure.
- `:tooling-telemetry` — `SduiTelemetry` interface with test-double implementation.
- Contract test fixtures in `:protocol-fixtures`.
- Architecture decision records for every non-obvious choice made so far.

**Demo:** A designer can open the preview harness, edit a JSON file, see their changes instantly. A PR that removes a field gets blocked by CI.

## M8 — Performance and accessibility (weeks 15–16)

**Goal:** Production-grade characteristics.

- Benchmark suite: 1,000-node screens, 10,000-item lists, patch storms.
- Protobuf serialization as an opt-in transport for heavy screens.
- Accessibility audit: screen reader traversal, focus order, content descriptions on all widgets.
- Performance budget enforced in CI: p99 screen render under 16 ms on reference device.
- WCAG AA compliance for the reference design system.

**Demo:** A 5,000-row transaction list scrolling smoothly. Full screen reader walkthrough of a form.

## M9 — Documentation, samples, 1.0 release (weeks 17–18)

**Goal:** External adoption is possible.

- Full API documentation (Dokka).
- Getting-started guide.
- Migration guide (even for v1 — the template matters more than the content).
- Five sample apps covering: feed, e-commerce, forms, messaging, dashboard.
- Protocol snapshot tagged `v1.0.0`.
- Public release.

**Demo:** A new developer can `gradle init` from the getting-started guide and have a working SDUI app in under 30 minutes.

---

## Post-1.0 backlog (do not start before M9)

- Additional widget modules (charts, rich text, maps config DSL).
- Additional transports (gRPC-web, Server-Sent Events for environments without WebSocket).
- IDE plugin for protocol validation.
- Visual tree editor in the preview harness.
- Experiment framework integration (flag-driven screen variants).
- Multi-tenant server adapters (Spring, http4k).

Everything on this list is deferred deliberately. The discipline of not building these during M0–M9 is what keeps the core simple.
