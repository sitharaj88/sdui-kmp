// iOS sample shared module.
//
// Produces a `SduiSampleShared.framework` consumed by the hand-authored Xcode project at
// `iosApp/` via `embedAndSignAppleFrameworkForXcode` — see ADR-0011 for why this module
// applies the raw KMP plugin instead of `sdui.kmp.library`.
//
// Targets are limited to the three iOS triples the framework's library modules already
// publish for. Compose Multiplatform is on the classpath so we can return a
// `ComposeUIViewController` from a Kotlin factory function consumed by Swift via interop.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    // Mirror the discipline applied by `sdui.kmp.library` — every public API needs KDoc.
    explicitApi()
    jvmToolchain(17)

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            // The framework's Objective-C symbol prefix is derived from this base name —
            // keep it stable so the Xcode `Run Script` build phase, the `import` in
            // `ContentView.swift`, and any future XCFramework consumers don't drift.
            baseName = "SduiSampleShared"
            // Dynamic so the Xcode `Embed & Sign` phase actually has something to sign;
            // a static framework would only need linking, but the standard
            // `embedAndSignAppleFrameworkForXcode` Gradle task assumes dynamic.
            isStatic = false
        }
    }

    sourceSets {
        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":runtime"))
                implementation(project(":widgets-core"))
                implementation(project(":widgets-forms"))
                implementation(project(":widgets-media"))
                implementation(project(":widgets-media-coil"))
                implementation(project(":widgets-nav"))
                implementation(project(":transport-http"))
                implementation(project(":transport-live"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.darwin)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosX64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}
