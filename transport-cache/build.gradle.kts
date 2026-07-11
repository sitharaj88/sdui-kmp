plugins {
    id("sdui.kmp.library")
    alias(libs.plugins.kotlinSerialization)
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp screen disk cache: ScreenDiskCache interface and per-platform default " +
            "implementations enabling offline-first transports and last-known-good replay.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            api(project(":runtime"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Wasm browser tests fail to resolve Compose/skiko runtime resources transitively pulled in
// via :runtime, even though this module is platform-independent storage logic. JVM tests
// already cover the behavior; Wasm `compileKotlinWasmJs` still exercises the API contract.
//
// Also disable the test-development-executable compile and any downstream test sync — these
// tasks try to bundle the full Compose+skiko runtime into a JS executable and OOM the Kotlin
// compiler on machines with < 8 GB of free heap. Gating them off doesn't reduce coverage
// because the tests themselves are also disabled (no executable would ever run).
tasks.matching {
    it.name == "wasmJsBrowserTest" ||
        it.name == "wasmJsTest" ||
        it.name == "compileTestDevelopmentExecutableKotlinWasmJs" ||
        it.name == "wasmJsTestTestDevelopmentExecutableCompileSync"
}.configureEach {
    enabled = false
}
