# ADR 0011 — iOS sample uses a custom KMP block, not `sdui.kmp.library`

**Status:** accepted (Phase P23)

## Context

Phases 0–9 shipped sample apps for Android, Desktop (JVM), Wasm, and a JVM server,
all of which sit under `samples/`. iOS was always a target the framework's library
modules (`:runtime`, `:widgets-*`, `:transport-*`, `:widgets-media-coil`) compiled for
— `iosArm64`, `iosX64`, and `iosSimulatorArm64` — but there was no end-to-end iOS sample
proving a Compose Multiplatform iOS host can render a server-driven screen.

Phase P23 adds that sample. It needs a Gradle module that exposes a shared
`ComposeUIViewController` factory consumable from a hand-authored Xcode project at
`iosApp/`.

The library convention plugin `id("sdui.kmp.library")` is the obvious starting point —
every shipping module uses it. But it bundles four non-iOS targets:

```kotlin
// build-logic/src/main/kotlin/sdui.kmp.library.gradle.kts
androidTarget { /* ... */ }
jvm()
iosArm64(); iosSimulatorArm64(); iosX64()
wasmJs { browser() }
```

Applying it to an iOS-only sample would force us to:

- Wire an `android { namespace = "..." minSdk = ... }` block (unused; sample never builds for Android).
- Author dummy `androidMain`/`jvmMain`/`wasmJsMain` source sets to satisfy the convention.
- Pay the configuration cost of the Android Gradle plugin and Wasm Node download on every CI run that touches `:samples:sample-ios` — even though those targets contribute nothing.

## Decision

`samples/sample-ios/build.gradle.kts` does **not** apply `sdui.kmp.library`. It applies
the raw `kotlin("multiplatform")` plugin plus `compose-multiplatform`, and declares only
the three iOS targets:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SduiSampleShared"
            isStatic = false
        }
    }
    sourceSets {
        iosMain.dependencies { /* runtime + widgets + transport + ktor-darwin + coil */ }
    }
}
```

Explicit API mode is enabled manually inside the `kotlin {}` block so KDoc-on-public-types
discipline still applies — the convention plugin's `explicitApi()` call is what we lose
by not applying it.

## Rationale

- **No phantom targets.** The sample compiles for exactly the platforms that consume it.
  CI's `:samples:sample-ios:compileKotlinIosSimulatorArm64` is a focused signal; nothing
  else can break it.
- **Faster local iteration.** No Android Gradle plugin in the dependency graph, no
  manifest, no R class generation, no Wasm Node bootstrap. The sample's `tasks --all`
  output stays narrowly iOS-focused.
- **Tier classification still works.** `classifyTier` in the root `build.gradle.kts`
  routes any path under `:samples:*` into `ModuleTier.SAMPLE`, which has no outbound
  restrictions, so the unconventional plugin set doesn't trip `verifyDependencyRules`.
- **Other samples set the precedent.** `:samples:sample-wasm` already skips the
  convention plugin and applies `kotlinMultiplatform` directly because it only needs the
  `wasmJs` target. iOS follows the same pattern.

## Consequences

- The sample-ios module has its own `explicitApi()` declaration, its own
  `jvmToolchain(17)` line, and its own iOS-target list. If we ever change the iOS target
  triple (e.g. drop `iosX64` once the simulator-on-x86 fleet retires), this file needs
  the same edit as `sdui.kmp.library`. That's a one-line drift, and the sample's
  `compileKotlin*` task names are stable enough that CI catches drift immediately.
- If a future iOS-only library module emerges, it should either follow this pattern or
  motivate a new `sdui.kmp.ios.library` convention. We do not preemptively extract one
  for a single consumer.
- The `:runtime`/`:widgets-*`/`:transport-*` modules continue to use `sdui.kmp.library`
  because they really do ship to all five platforms; the convention plugin still earns
  its keep there.
