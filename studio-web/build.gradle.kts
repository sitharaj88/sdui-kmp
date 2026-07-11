plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
    // Frontend bundles aren't libraries (no sdui.kmp.library / sdui.compose.library), but we
    // still want the same lint coverage as the rest of the repo. Applying the detekt
    // convention directly gives us ktlint-equivalent checks without pulling in publish/Android
    // wiring that doesn't apply to a wasm-only browser app.
    id("sdui.detekt")
}

// :studio-web is a frontend application bundle. It is intentionally NOT published to Maven
// Central (no `sdui.publish`) — the only consumers are Studio operators, who load it via the
// generated JS bundle hosted next to the studio-server.
//
// Wasm-only: the Studio is a browser admin console. No Android/iOS/Desktop/JVM targets here;
// when we want a desktop wrapper for offline editing, that becomes a separate module.

kotlin {
    jvmToolchain(17)

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "sdui-kmp-studio-web"
        browser {
            commonWebpackConfig {
                outputFileName = "sdui-kmp-studio-web.js"
                // Pin the dev server to 8082 so it does not collide with the end-user
                // sample-server (8080) or the studio-server backend (8081) during a full
                // local Studio run. studio-server's dev CORS allowlists exactly this origin.
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).copy(
                    port = 8082,
                )
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            // The whole point of the Studio is that the live preview pane uses the same
            // SduiHost the production clients use — so the same widget modules must be on
            // the classpath as in :samples:sample-wasm.
            implementation(project(":runtime"))
            implementation(project(":widgets-core"))
            implementation(project(":widgets-forms"))
            implementation(project(":widgets-media"))
            implementation(project(":widgets-nav"))
            implementation(project(":transport-http"))

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }

        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Wasm browser tests are flaky in this repo (skiko/Compose resource bundling OOMs the Kotlin
// compiler on machines with constrained heap; see :transport-cache and :tooling-telemetry for
// the same workaround). The S5 effort that lands the visual editor will revisit this; for the
// skeleton phase we rely on JVM tests at the API-wrapper boundary that the parallel agent owns,
// plus manual smoke checks in the browser dev server.
tasks.matching {
    it.name in setOf(
        "wasmJsBrowserTest",
        "wasmJsTest",
        "compileTestDevelopmentExecutableKotlinWasmJs",
        "wasmJsTestTestDevelopmentExecutableCompileSync",
    )
}.configureEach {
    enabled = false
}
