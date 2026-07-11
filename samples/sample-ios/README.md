# :samples:sample-ios

Shared Kotlin Multiplatform module for the sdui-kmp iOS sample. Produces a
`SduiSampleShared.framework` consumed by [`iosApp/`](../../iosApp/), the hand-authored
Xcode project that hosts the Compose Multiplatform UI on iOS.

This module is the iOS counterpart of [`:samples:sample-android`](../sample-android/),
[`:samples:sample-desktop`](../sample-desktop/), and [`:samples:sample-wasm`](../sample-wasm/).
The Compose code is intentionally a near-verbatim port of `:samples:sample-desktop`'s
`Main.kt` — same `SduiHost`, same Coil 3 image loader, same Sign in / Sign out toolbar,
same WebSocket live-reload subscription on `/live/screens/home`.

See [ADR-0011](../../docs/adr/0011-ios-sample-target-strategy.md) for why this module
applies the raw `kotlinMultiplatform` plugin instead of the standard
`sdui.kmp.library` convention.

## Prerequisites

> **Full Xcode is required** (not just Command Line Tools). On a Mac with only
> `xcode-select --install`, `compileKotlinIosSimulatorArm64` will succeed but the
> link tasks (`linkDebugFrameworkIosSimulatorArm64`, etc.) and `xcrun simctl` will
> fail. CI's `macos-14` runner has full Xcode and runs the iOS build end-to-end.

- macOS with Xcode 15+
- iOS 16+ Simulator runtime
- JDK 17+
- The sample server running on `http://localhost:8080`:
  ```bash
  ./gradlew :samples:sample-server:run
  ```

## Run the iOS sample

The shared module is consumed by the Xcode project at [`iosApp/`](../../iosApp/).
Open it in Xcode, pick an iOS simulator, and press **Cmd+R**:

```bash
./gradlew :samples:sample-server:run    # terminal 1 — Ktor server on :8080
open iosApp/iosApp.xcodeproj            # terminal 2 — Xcode opens; pick a simulator and Run
```

See [`iosApp/README.md`](../../iosApp/README.md) for the full Xcode walkthrough,
including how to recover if the committed pbxproj fails to open.

## Verify the shared module builds (no Xcode needed)

```bash
./gradlew :samples:sample-ios:compileKotlinIosSimulatorArm64
```

This succeeds with only Command Line Tools installed — useful as a local smoke test
when you're editing the shared Compose code on a Mac that doesn't have a full Xcode.

The Xcode-driven framework embed task is:

```bash
./gradlew :samples:sample-ios:embedAndSignAppleFrameworkForXcode
```

This task is generated automatically by the Kotlin Multiplatform plugin when the
module declares `binaries.framework { ... }` for an iOS target. It's invoked by the
`Run Script` build phase in `iosApp.xcodeproj`, which wraps the standard Kotlin/Native
link tasks (`linkDebugFrameworkIosSimulatorArm64`, etc.) and copies the resulting
framework into Xcode's `FRAMEWORKS_FOLDER_PATH` with the right signature. The task
reads `CONFIGURATION`, `SDK_NAME`, and `ARCHS` from Xcode's environment, so it
"just works" with no extra wiring on either side.

## Module structure

```
samples/sample-ios/
├── build.gradle.kts                                  KMP iOS-only build (see ADR-0011)
└── src/iosMain/kotlin/dev/sdui/kmp/sample/ios/
    └── SduiSampleViewController.kt                   ComposeUIViewController factory consumed by Swift
```

The single Kotlin entry point is:

```kotlin
public fun SduiSampleViewController(
    baseUrl: String = DEFAULT_BASE_URL,
): UIViewController
```

which returns a `ComposeUIViewController { ... }` rendering `SduiHost` against an
`HttpScreenSource` pointed at `baseUrl`. Swift consumes it via the generated Obj-C
header as `SduiSampleViewControllerKt.SduiSampleViewController(baseUrl:)`.

## Why the targets list is hand-rolled

`build.gradle.kts` does not apply `id("sdui.kmp.library")` because that convention
plugin adds Android, JVM, and Wasm targets the iOS sample doesn't need. The trade-off
and migration story are documented in
[ADR-0011](../../docs/adr/0011-ios-sample-target-strategy.md).
