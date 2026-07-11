import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":widgets-core"))
    implementation(project(":widgets-forms"))
    implementation(project(":widgets-media"))
    implementation(project(":widgets-media-coil"))
    implementation(project(":widgets-nav"))
    implementation(project(":transport-http"))
    // Live transport for the desktop sample: subscribes to `/live/screens/home` so the
    // home screen hot-reloads when an editor publishes via the studio. Per-screen wiring
    // is a polish item — see Main.kt for the limitation.
    implementation(project(":transport-live"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.material3)
    implementation(compose.foundation)
}

compose.desktop {
    application {
        mainClass = "dev.sdui.kmp.sample.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "sdui-kmp-desktop"
            packageVersion = "1.0.0"
        }
    }
}
