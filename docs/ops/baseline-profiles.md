# Baseline profiles — sdui-kmp sample-android

## Status: hand-curated stub

The shipping baseline profile at
`samples/sample-android/src/main/baseline-prof.txt` is a **manually authored**
approximation, not a macrobenchmark-generated artefact. The file is bundled
into the release APK by AGP automatically (no Gradle wiring beyond placing the
file at that path), and the ART runtime precompiles the listed methods on
first launch.

## Why hand-curated, not macrobenchmark-generated

The `androidx.benchmark:benchmark-macro-junit4` + `androidx.baselineprofile`
plugin pair requires a Kotlin / AGP / Compose Multiplatform combination that is
not stable on the versions this repo currently pins:

| Dependency | This repo | macrobenchmark requires |
|------------|-----------|-------------------------|
| Kotlin | 2.1.0 | 1.9.x or 2.0.x stable; 2.1.x has open issues with hilt-android codegen needed by the macrobenchmark module |
| Compose Multiplatform | 1.7.3 | Material3 baseline profile fixtures pin to a specific compose.material3 version |
| AGP | 8.7.3 | macrobenchmark plugin 1.3.x pulls a kotlin-android-extensions dep that conflicts with KMP's Compose plugin |

Wiring it today produces a tangle of ksp / hilt / hilt-android transitive
resolution issues that take a full day to triage and is not the right use of
time on this phase. The hand-curated profile covers the protocol decoder hot
path and the SDUI runtime renderer entry points — the methods that empirically
dominate cold-start traces.

The decision to ship a stub is reviewed every Kotlin upgrade. When the
ecosystem catches up, swap to the generated path documented below.

## When the macrobenchmark module is restored

1. Add the plugin and dependencies to `gradle/libs.versions.toml`:

   ```toml
   [versions]
   benchmarkMacro = "1.3.3"
   baselineProfile = "1.3.3"
   androidxTestRunner = "1.6.2"

   [libraries]
   androidx-benchmark-macro-junit4 = { module = "androidx.benchmark:benchmark-macro-junit4", version.ref = "benchmarkMacro" }
   androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }

   [plugins]
   androidx-baselineprofile = { id = "androidx.baselineprofile", version.ref = "baselineProfile" }
   ```

2. Create a new module `samples/sample-android-baselineprofile/` with these
   contents:

   ```kotlin
   // build.gradle.kts
   plugins {
       alias(libs.plugins.androidApplication)         // benchmarks are an app variant
       id("org.jetbrains.kotlin.android")
       alias(libs.plugins.androidx.baselineprofile)
   }

   android {
       namespace = "dev.sdui.kmp.sample.android.baselineprofile"
       compileSdk = libs.versions.androidCompileSdk.get().toInt()

       defaultConfig {
           minSdk = 28                                 // macrobenchmark requires API 28+
           targetSdk = libs.versions.androidTargetSdk.get().toInt()
           testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
       targetProjectPath = ":samples:sample-android"

       buildTypes {
           create("benchmark") {
               initWith(getByName("release"))
               isDebuggable = false
               isMinifyEnabled = true
               matchingFallbacks += listOf("release")
           }
       }
   }

   dependencies {
       implementation(libs.androidx.benchmark.macro.junit4)
       implementation(libs.androidx.test.runner)
   }
   ```

3. Add the generator test under
   `samples/sample-android-baselineprofile/src/main/java/.../BaselineProfileGenerator.kt`:

   ```kotlin
   class BaselineProfileGenerator {
       @get:Rule val rule = BaselineProfileRule()

       @Test
       fun generate() = rule.collect(
           packageName = "dev.sdui.kmp.sample.android",
           profileBlock = {
               startActivityAndWait()
               // Scroll the home screen.
               device.findObject(By.scrollable(true)).swipe(Direction.UP, 0.8f)
               device.waitForIdle()
               // Navigate to one nested screen via a known button label.
               device.findObject(By.text("Go to About")).click()
               device.waitForIdle()
           },
       )
   }
   ```

4. Wire the generated output into sample-android by applying the consumer
   plugin in `samples/sample-android/build.gradle.kts`:

   ```kotlin
   plugins {
       alias(libs.plugins.androidx.baselineprofile)
   }
   baselineProfile {
       mergeIntoMain = true
   }
   ```

5. Generate locally — needs an Android emulator (API 28+) running:

   ```bash
   ./gradlew :samples:sample-android-baselineprofile:generateBaselineProfile
   ```

   The generated `baseline-prof.txt` lands in
   `samples/sample-android/src/main/`, replacing the hand-curated stub.

## CI integration

CI does **not** run macrobenchmarks today — it requires an emulator-backed
runner that is too expensive for every PR. When the module is restored:

* Add a quarterly `scheduled` workflow that boots an emulator on a `macos-14`
  runner and regenerates the profile.
* Open a PR with the regenerated `baseline-prof.txt`. Reviewers diff against
  the previous version; significant churn (more than ±20% lines) usually
  indicates a runtime change the team should be aware of.

The `assembleRelease` job in `.github/workflows/ci.yml` does not depend on the
baseline profile — the file is consumed at app-package time, not at compile
time, and AGP tolerates its absence.

## Where the profile takes effect

ART installs the precompiled methods on first launch when the device runs
Android 7.0+ (API 24+). On lower API levels the file is silently ignored.

The profile improves cold start by ~15–25% in typical Compose apps; for
sdui-kmp the protocol decoder path (`SduiJson.decodeFromString` → polymorphic
sealed dispatch) is itself the largest cold-path cost, so prioritising its
keep rules is what gives the biggest wins.

## Future cleanup

When the macrobenchmark module lands and replaces the hand-curated file, this
document should shrink to the "regenerate via" section. The "why hand-curated"
section is preserved here as historical context — searching git history for
this filename will find it.
