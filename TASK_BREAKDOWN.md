# Task Breakdown — M0, M1, M2

Every task below has a type, estimated effort, and explicit acceptance criteria. Claude Code should work through these in order. Do not skip ahead — M1 depends on M0 structure, M2 depends on M1 types.

Effort scale: **XS** (< 1 hour), **S** (1–3 hours), **M** (half a day), **L** (full day), **XL** (multi-day).

## M0 — Bootstrap

### M0.1 — Gradle bootstrap — L

Create the root Gradle project. Install toolchains.

- `settings.gradle.kts` with all module declarations from `docs/ARCHITECTURE.md` (even empty).
- `gradle/libs.versions.toml` with Kotlin, Compose MP, Ktor, kotlinx-serialization, kotlinx-coroutines, kotlinx-collections-immutable versions.
- Root `build.gradle.kts` with repositories and plugin management.
- `.gitignore` for Gradle, IntelliJ, Xcode, Android Studio artifacts.
- JDK 17 toolchain.

**Acceptance:** `./gradlew projects` lists every module from the architecture doc.

### M0.2 — Convention plugins — L

Four plugins in `build-logic/`:

- `sdui.kmp.library` — multiplatform library with Android, iOS, Desktop, Wasm, JVM targets.
- `sdui.kmp.module` — `kmp.library` plus kotlinx-serialization plugin configured.
- `sdui.jvm.library` — JVM-only library (for `:server`).
- `sdui.compose.library` — `kmp.library` plus Compose Multiplatform plugin.

Each plugin applies its dependencies, sets the Kotlin version, and enables strict explicit API mode.

**Acceptance:** Each plugin can be applied to a module and the module builds. Explicit API mode is on.

### M0.3 — Dependency rules enforcement — M

Add a Gradle task `verifyDependencyRules` that fails the build if:

- Any module other than `protocol` has a dependency edge back into `protocol`'s dependencies.
- `runtime` depends on `server` or vice versa.
- Any `widgets-*` depends on another `widgets-*`.

Hook into `check`.

**Acceptance:** Adding a forbidden dependency fails `./gradlew check`. Removing it passes.

### M0.4 — CI pipeline — M

`.github/workflows/ci.yml`:

- Linux job: build `protocol`, `runtime`, `server`, JVM and Android targets, run JVM tests.
- macOS job: build iOS and Desktop targets, run iOS simulator tests.
- Wasm job on Linux: build Wasm target.

**Acceptance:** A PR runs all three jobs. All pass on the empty scaffold.

### M0.5 — Sample app shells — L

Five minimal apps that display "Hello, sdui-kmp":

- `sample-android` — Android app with a `ComponentActivity` and a single `Text("Hello")`.
- `sample-ios` — iOS app (Xcode project in `iosApp/`) that loads a Compose Multiplatform view controller.
- `sample-desktop` — JVM app with a Compose window.
- `sample-wasm` — Wasm entry point rendering into `<div id="root">`.
- `sample-server` — Ktor server with a `GET /health` endpoint returning `{"status":"ok"}`.

**Acceptance:** `./gradlew :samples:sample-android:installDebug` installs and launches on an emulator. `./gradlew :samples:sample-desktop:run` opens a window. `./gradlew :samples:sample-server:run` serves `/health`.

---

## M1 — Protocol v0 and smallest renderer

### M1.1 — Core value classes and IDs — S

In `:protocol/commonMain`:

- `NodeId` (value class, String-backed).
- `StatePath` (value class, String-backed, with `child()`).
- `SchemaVersion` (value class, Int-backed, `Comparable`).
- `ScreenId` (value class, String-backed).

All `@Serializable`. All with `kotlin.test` round-trip tests in `:protocol/commonTest`.

**Acceptance:** `Json.encodeToString` and `decodeFromString` round-trip every type. Tests pass on JVM, Android, iOS, Desktop, Wasm.

### M1.2 — Sealed node hierarchy — M

In `:protocol/commonMain`:

- `UiNode` sealed interface with `id`, `since`, `fallback`.
- `Container` sealed interface extending `UiNode` with `children: List<UiNode>`.
- `Leaf` sealed interface extending `UiNode`.
- `SerializersModule` exporter function for host apps to register with their `Json` instance.

**Acceptance:** A parameterized test encodes and decodes every node type via the exported serializers module with `classDiscriminator = "type"`.

### M1.3 — Value, Action, Predicate — M

In `:protocol/commonMain`:

- `Value<T>` sealed interface with `Literal` and `Bind` (Template is M3).
- `Action` with `Navigate` and `UpdateState` only (others are M3).
- `Destination` with `ScreenDest` and `Back` only.
- `Predicate` with full hierarchy (cheap to include now, harder to retrofit).
- Round-trip tests for every variant.

**Acceptance:** Nested actions and destinations serialize correctly. A `Navigate(ScreenDest("/home"))` round-trips to byte-identical JSON.

### M1.4 — Design tokens — S

In `:protocol/commonMain`:

- `ColorToken` sealed interface with all eight objects.
- `Spacing`, `TextStyleToken`, `RadiusToken`, `ElevationToken` enums.
- `IconToken` sealed interface.
- `EdgeInsets` data class with symmetric + directional constructors.
- Round-trip tests.

**Acceptance:** Every token has a `@SerialName` that matches a documented string table. Linter can parse them.

### M1.5 — A11y type — S

In `:protocol/commonMain`:

- `A11y` data class and `A11yRole`, `LiveRegion` enums.
- Present on every widget defined in M1.6.

**Acceptance:** A `Text` with full a11y metadata round-trips. A `Text` with `a11y = null` round-trips.

### M1.6 — The three widgets — M

In `:protocol/commonMain`:

- `Column` (Container) with spacing, padding, `a11y`.
- `Text` (Leaf) with `content: Value<String>`, `style: TextStyleToken`, `color: ColorToken?`, `a11y`.
- `Button` (Leaf) with `label: Value<String>`, `action: Action`, `style: ButtonStyle`, `a11y`.

**Acceptance:** Each widget round-trips. Each widget renders its JSON via the registry in M1.9.

### M1.7 — Screen envelope — XS

In `:protocol/commonMain`:

- `Screen` data class with all documented fields.
- `ScreenMetadata` data class.
- `StateDeclaration` data class (scope field defaults to Global; full scopes come in M3).

**Acceptance:** A full `Screen` round-trips.

### M1.8 — StateStore v0 (flat) — S

In `:runtime/commonMain`:

- Flat `StateStore` with `read`, `update`, `patch` (no scopes yet — stub the `child` method to return `this`).
- Uses `kotlinx.collections.immutable.PersistentMap`.
- Exposes a `State<PersistentMap<StatePath, JsonElement>>` for Compose recomposition.

**Acceptance:** Updates trigger recomposition. Multiple widgets bound to the same path update together.

### M1.9 — WidgetRegistry and NodeRenderer — M

In `:runtime/commonMain`:

- `NodeRenderer<T>` interface.
- `WidgetRegistry` with `Builder`.
- `LocalRegistry`, `LocalStateStore`, `LocalActionDispatcher`, `LocalTelemetry` composition locals.
- `RenderNode` composable with the fallback branch.
- `SduiHost` composable with Loading, Error, Ready states.
- `NoopTelemetry` object.

**Acceptance:** A `Screen` containing an unknown node type renders the fallback. A screen with no fallback renders nothing for that node but does not crash. Telemetry `onUnknownNode` is called.

### M1.10 — Renderer implementations for the three widgets — M

In `:widgets-core/commonMain`:

- `ColumnRenderer`, `TextRenderer`, `ButtonRenderer`.
- A `WidgetsCore` object with a `register(builder: WidgetRegistry.Builder)` function.

**Acceptance:** Host app calls `WidgetsCore.register(builder)` and renders a screen with all three widget types.

### M1.11 — Server DSL v0 — M

In `:server/jvmMain`:

- `screen(id, block)` top-level function.
- `ScreenScope` with `column`, `text`, `button`.
- Deterministic `NodeId` generation (stable across restarts for same call site).
- `Json` configuration helper for server output.

**Acceptance:** A DSL snippet produces byte-identical JSON on two server runs.

### M1.12 — HttpScreenSource — M

In `:transport-http/commonMain`:

- `HttpScreenSource` class as documented.
- `ScreenState` sealed interface (Loading, Error, Ready).
- `ScreenSource` interface.
- No ETag caching yet — that is M5.

**Acceptance:** Sample Android app fetches a screen from sample server and renders it.

### M1.13 — Sample server screen — S

In `:samples:sample-server`:

- `GET /screens/home` endpoint returning a DSL-built screen with a Column, Text, and Button.
- The Button's action is a `Navigate(ScreenDest("/about"))`.
- `GET /screens/about` returns a simpler screen.

**Acceptance:** Android sample connects, renders Home, tapping Button navigates to About.

### M1.14 — M1 end-to-end test — M

Integration test that:

1. Starts the sample server on a random port.
2. Launches the Android instrumentation test on an emulator.
3. Verifies Text content matches server output.
4. Verifies navigation works.

**Acceptance:** Test runs on CI and passes.

---

## M2 — All platforms rendering

### M2.1 — iOS sample wiring — L

- Xcode project in `iosApp/`.
- `shared` Compose MP view controller exposing `SduiHost`.
- Ktor Darwin client engine.
- `:sample-ios` renders the same Home screen as Android.

**Acceptance:** iOS simulator renders the screen identically to Android (same tokens, same layout).

### M2.2 — Desktop sample wiring — M

- `:sample-desktop` uses Compose for Desktop.
- Ktor Java client engine.
- Window sized to approximate mobile viewport for consistent testing.

**Acceptance:** Desktop app renders the screen. `./gradlew :samples:sample-desktop:run` works on macOS, Linux, Windows.

### M2.3 — Wasm sample wiring — L

- `:sample-wasm` with `@Composable` root rendering into a browser `div`.
- Ktor Js client engine.
- CORS configured on sample server for localhost Wasm dev.

**Acceptance:** `./gradlew :samples:sample-wasm:wasmJsBrowserRun` serves a page that renders the screen.

### M2.4 — Navigation integration — L

In `:runtime/commonMain`:

- `Navigator` interface with `push`, `pop`, `replace`, `popToRoot`, `switchTab`.
- `rememberNavigator()` composable factory.
- Platform implementations:
  - Android uses Jetpack Navigation Compose under the hood.
  - Everything else uses a shared Voyager- or Decompose-based implementation in commonMain.

**Acceptance:** `Navigate` action works on all five platforms. Back button works on Android.

### M2.5 — Golden snapshot test infrastructure — L

- Paparazzi for Android.
- Roborazzi for other targets where feasible.
- Golden images stored in `src/commonTest/resources/screenshots/`.
- `recordGolden` Gradle task to regenerate.
- CI task `verifyGolden` compares pixel output.

**Acceptance:** Each of `Column`, `Text`, `Button` has at least one golden test. Intentionally breaking one fails CI.

### M2.6 — Contract test fixtures — M

- New module `:protocol-fixtures` with curated JSON trees covering every widget, every action, every value variant.
- Server DSL test: assert DSL-built trees match fixtures byte-for-byte.
- Runtime rendering test: assert every fixture renders without falling back.

**Acceptance:** Fixture tests pass on all five platforms. A PR adding a new widget must add a fixture.

### M2.7 — M2 demo script — XS

Documentation at `samples/README.md` with five commands to run the same screen on five platforms. Include screenshots.

**Acceptance:** A new developer can follow the README end to end in 15 minutes.

---

## How Claude Code should work through these

For each task:

1. Read the acceptance criteria.
2. Check `docs/ARCHITECTURE.md` for the relevant section.
3. Check `docs/CONVENTIONS.md` for style rules.
4. Implement the task.
5. Write tests that verify the acceptance criteria.
6. Run `./gradlew check` locally.
7. Open a PR titled `Mx.y — Task title`.

**Do not batch tasks across milestone boundaries.** Complete all of M0 before starting M1. Complete all of M1 before starting M2. This is how we catch protocol mistakes at M1 instead of M7.

**If a task is unclear, stop and ask.** It is cheaper to clarify a requirement than to undo a week of work built on a wrong assumption.
