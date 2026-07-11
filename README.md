# sdui-kmp

A server-driven UI framework for Kotlin Multiplatform, designed for a 5–10 year lifespan.

Servers emit typed, versioned UI trees in Kotlin DSL. Clients render them with Compose
Multiplatform. The server DSL and client renderer share the same `kotlinx.serialization`-
backed sealed hierarchy — no IDL, no codegen, no schema drift. The framework runs on
**Android, iOS, Desktop (JVM), Web (Wasm), and Kotlin/JVM server** from a single `:protocol`
module.

## Quick start

```bash
git clone <this-repo>
cd sdui-kmp
./gradlew :samples:sample-server:run        # terminal 1 — Ktor server on :8080
./gradlew :samples:sample-desktop:run        # terminal 2 — Compose desktop client
```

Five demo screens:
- **Home** — basic Column + Text + Button
- **Login** — `TextField` + `Checkbox` + `Action.Submit` with optimistic state, retry, rollback
- **Feed** — `LazyList` with per-item state scoping (each row's "Liked" toggle is independent)
- **About** — back-navigation
- **Tracking** — `NativeSurface` (with fallback when no map factory is registered) + `AsyncImage` + template-bound ETA

Walk through it: **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)**.

## What's here

```
sdui-kmp/
├── protocol/                 Tier 1. The wire types — sealed hierarchies, value classes, tokens.
├── protocol-fixtures/        JSON corpus + contract tests for every protocol shape.
├── runtime/                  Tier 2. SduiHost, StateStore, NodeRenderer, dispatcher, navigator.
├── server/                   Tier 2. Kotlin DSL: screen { column { text(...) button(...) } }.
├── widgets-core/             Tier 3. Renderers for Column, Text, Button, LazyList.
├── widgets-forms/            Tier 3. TextField, Checkbox, Validation evaluator.
├── widgets-media/            Tier 3. Image, AsyncImage, ImageLoader seam.
├── transport-http/           Tier 3. HttpScreenSource (with ETag caching) + KtorSubmitHandler.
├── transport-live/           Tier 3. WebSocketLiveSource for live state/tree updates.
├── tooling-cli/              Tier 0. Schema linter + protocol-snapshot.json baseline.
├── tooling-preview/          Tier 0. File-watching Compose Desktop preview app.
├── tooling-telemetry/        Tier 0. RecordingTelemetry test double.
├── benchmarks/               Tier 0. Microbenchmarks for hot paths.
└── samples/
    ├── sample-android/       Android client.
    ├── sample-desktop/       Compose for Desktop client.
    ├── sample-ios/           Compose Multiplatform iOS shared module (consumed by iosApp/).
    ├── sample-wasm/          Compose Multiplatform Wasm browser client.
    └── sample-server/        Ktor server with /screens/* + /auth/login + /live/ticker.

The iOS sample also has a hand-authored Xcode project at `iosApp/` — see
[`iosApp/README.md`](iosApp/README.md) for run instructions. Full Xcode (not just
Command Line Tools) is required to build and launch on a simulator.
```

Module dependency rules — enforced by `./gradlew verifyDependencyRules` (M0.3, planned):

- `:protocol` depends on `kotlinx.serialization` + `kotlinx.collections.immutable`. Nothing else.
- `:runtime` and `:server` both depend on `:protocol`. Never on each other.
- `widgets-*` depend on `:runtime` only. Never on each other.
- `transport-*` depend on `:runtime` plus their platform Ktor engine.

## The five non-negotiables

From [VISION.md](VISION.md):

1. **One protocol, both sides.** Server DSL and client renderer share the same sealed
   hierarchy in `:protocol`. No IDL, no codegen.
2. **Additive-only evolution.** Fields added, never removed. Enum cases added, never
   repurposed. Enforced by `./gradlew verifyProtocolSnapshot`.
3. **Client never crashes on unknown nodes.** Every node has `since: SchemaVersion` and an
   optional `fallback: UiNode`. Unknown discriminator → render fallback (or nothing).
4. **Actions are data, not code.** `Action` is a sealed data class hierarchy. Enables
   offline queues, optimistic updates, retry, idempotency, replay.
5. **Semantic tokens only.** `ColorToken.Surface`, `Spacing.Md`, `TextStyleToken.Heading`.
   No hex colors, pixel sizes, or font names cross the wire.

## Documentation

- **[GETTING_STARTED.md](docs/GETTING_STARTED.md)** — 30-minute tour from `git clone` to a working app
- **[EXTENSION_GUIDE.md](docs/EXTENSION_GUIDE.md)** — how to add custom widgets, native surfaces, telemetry, image loaders
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — full technical reference for every protocol type, runtime contract, transport
- **[VISION.md](VISION.md)** — what we are building and why; the five non-negotiables in detail
- **[ROADMAP.md](ROADMAP.md)** — milestones M0 through M9, status, what's next
- **[CONVENTIONS.md](CONVENTIONS.md)** — code style, module rules, commit discipline
- **[MIGRATION.md](docs/MIGRATION.md)** — version-by-version migration history (currently empty — first entry is at v1.0.0)
- **[adr/](docs/adr/)** — 10 architecture decision records for the non-obvious calls
- **API reference** — generate with `./gradlew dokkaHtmlMultiModule`, then open `build/dokka/htmlMultiModule/index.html`

## Verifying a working tree

```bash
./gradlew check \
  -x iosX64Test -x iosSimulatorArm64Test \
  -x linkDebugTestIosX64 -x linkDebugTestIosSimulatorArm64 -x linkDebugTestIosArm64
```

The iOS exclusions are needed when only Command Line Tools (not full Xcode) are installed.
CI's `macos-14` runner has Xcode and runs iOS in [the GitHub Actions workflow](.github/workflows/ci.yml).

The `:tooling-cli:check` task includes the schema linter — it walks `:protocol`'s
`@Serializable` types and fails if anything in [`protocol-snapshot.json`](protocol-snapshot.json)
was removed, retyped, or reordered. Additive changes pass; the snapshot is regenerated via
`./gradlew captureProtocolSnapshot` after a deliberate protocol change.

## Status

Phases 0–9 of the [ROADMAP](ROADMAP.md) are complete. The protocol surface is stable,
all five platforms render the same trees, the schema linter blocks breaking changes, and
the framework has been exercised end-to-end via the sample apps.

What's deliberately deferred (each is a focused phase if you want it):
- Real Google Maps / Apple Maps / ExoPlayer / AVPlayer / Biometric `NativeSurfaceFactory`
  implementations (the framework ships the seam — see [tracking demo](samples/sample-server/src/main/kotlin/dev/sdui/kmp/sample/server/Main.kt))
- Real `ImageLoader` implementation (Coil 3 multiplatform; the framework ships the seam)
- Disk cache for screens + offline action queue (multiplatform storage layer)
- Paparazzi / Roborazzi golden snapshot infrastructure
- Protobuf opt-in transport — see [ADR-0009](docs/adr/0009-binary-format-deferred.md)
- Maven Central release automation — local publish via `./gradlew publishAllToMavenLocal`
  is wired (see [docs/PUBLISHING.md](docs/PUBLISHING.md)); the OSSRH staging-and-release
  step still happens through the Sonatype web UI until `gradle-nexus-publish-plugin` lands.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). You may obtain a copy of the
License at <https://www.apache.org/licenses/LICENSE-2.0.txt>.
