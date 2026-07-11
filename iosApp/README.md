# iosApp — sdui-kmp iOS sample host

Hand-authored Xcode project that hosts the Compose Multiplatform UI exported from
[`:samples:sample-ios`](../samples/sample-ios/). The Kotlin module produces a
`SduiSampleShared.framework` consumed here via a `Run Script` build phase.

## Prerequisites

- **Full Xcode** (App Store install, not just Command Line Tools). The Kotlin/Native
  iOS link tasks and the simulator runtime both require a complete Xcode install — you
  can verify with `xcode-select -p`, which should point at `…/Xcode.app/Contents/Developer`,
  not `…/CommandLineTools`.
- **iOS 16+ Simulator runtime.** Xcode → Settings → Platforms.
- **JDK 17+** for Gradle.
- **A running sample server.** From the repo root:
  ```bash
  ./gradlew :samples:sample-server:run
  ```
  Leave it running; the iOS app talks to `http://localhost:8080`. The simulator shares
  its network namespace with the host Mac so `localhost` works without any extra
  tunnelling.

## Run the sample

```bash
# 1. Bring up the server in one terminal.
./gradlew :samples:sample-server:run

# 2. Open the Xcode project.
open iosApp/iosApp.xcodeproj
```

In Xcode:

1. Pick a destination — **iPhone 15 (iOS 17)** is the default tested target.
2. Press **Cmd+R**. The first build runs the Gradle `embedAndSignAppleFrameworkForXcode`
   task, which kicks off `:samples:sample-ios:linkPodDebugFrameworkIosSimulatorArm64`
   (or the device-arm64 / x64 equivalent depending on simulator architecture). Expect
   the first build to take 1–3 minutes; subsequent builds reuse the Gradle cache.
3. The app launches on the home screen with a "Sign in (sample)" button at the top.

## Run the UI test

```bash
xcodebuild test \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 15'
```

The single test in [`iosAppUITests/iosAppUITests.swift`](iosAppUITests/iosAppUITests.swift)
verifies the app launches and the Compose-rendered toolbar is interactive.

> **Note:** The test target's pbxproj entry is included in the skeleton, but Xcode's
> built-in test runner only auto-discovers tests after you've opened the project once
> and let Xcode index it. If `xcodebuild test` fails with "no test bundle", open the
> project in Xcode → Product → Test (Cmd+U) once to prime the index, then re-run.

## If `iosApp.xcodeproj` fails to open

The committed pbxproj is intentionally minimal and may not survive every Xcode point
release. If Xcode reports "The project … cannot be opened":

1. Move (don't delete) the broken project: `mv iosApp/iosApp.xcodeproj iosApp/iosApp.xcodeproj.bak`.
2. **Xcode → File → New → Project → iOS App.**
3. Product Name: `iosApp`. Interface: **SwiftUI**. Language: **Swift**. Bundle ID:
   `dev.sdui.kmp.iosApp`. Tick **Include Tests**. Save into `iosApp/` (overwriting the
   stub). Make sure "Create Git repository" is **off** (we already have one).
4. Delete the auto-generated `iosAppApp.swift` and `ContentView.swift`. Drag the
   committed [`iosApp/iosAppApp.swift`](iosApp/iosAppApp.swift) and
   [`iosApp/ContentView.swift`](iosApp/ContentView.swift) into the iosApp target.
   Likewise drag [`iosAppUITests/iosAppUITests.swift`](iosAppUITests/iosAppUITests.swift)
   into the UI test target. Use the committed
   [`iosApp/Info.plist`](iosApp/Info.plist) — copy its `NSAppTransportSecurity`
   entry into the auto-generated one if Xcode insists on regenerating.
5. Add a **Run Script** build phase to the iosApp target. Drag it ABOVE "Compile
   Sources" so the framework exists before Swift compilation. Set the script body to:
   ```sh
   cd "$SRCROOT/.." && ./gradlew :samples:sample-ios:embedAndSignAppleFrameworkForXcode
   ```
6. In **Build Phases → Link Binary With Libraries**, add `SduiSampleShared.framework`
   from `samples/sample-ios/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)/`.
7. In **Build Settings → Search Paths → Framework Search Paths**, add:
   ```
   $(SRCROOT)/../samples/sample-ios/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
   ```
8. Build & run.

## How the Gradle integration works

The `Run Script` phase invokes a single Gradle task — `embedAndSignAppleFrameworkForXcode` —
which the Kotlin Multiplatform plugin generates automatically when the module declares
`binaries.framework { ... }` for an iOS target. The task:

1. Reads Xcode's `CONFIGURATION` (Debug/Release), `SDK_NAME`
   (iphonesimulator/iphoneos), and `ARCHS` env vars to pick the right Kotlin/Native
   triple.
2. Runs the corresponding `linkDebugFrameworkIosSimulatorArm64` /
   `linkReleaseFrameworkIosArm64` / etc.
3. Copies the resulting `.framework` to the path Xcode expects via the `FRAMEWORKS_FOLDER_PATH`
   env var, signing it with the Xcode-managed identity.

Because Xcode runs Gradle as a child process, the build directory is the repo root —
the leading `cd "$SRCROOT/.."` in the Run Script is what makes that work.

## Customising the server URL

Open [`iosApp/ContentView.swift`](iosApp/ContentView.swift) and edit the literal
`http://localhost:8080`. The Kotlin entry point exposes a `baseUrl` parameter so you
can also switch endpoints at runtime — for example by reading a UserDefaults key — by
passing the value through `ComposeView(baseUrl:)`.
