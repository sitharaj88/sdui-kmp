plugins {
    id("sdui.kmp.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp HTTP transport: HttpScreenSource with ETag caching plus KtorSubmitHandler " +
            "for delivering protocol trees and dispatching actions over HTTP.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":runtime"))
            api(project(":transport-cache"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

// Wasm test executable bundles the full Compose+skiko runtime via the transitive :runtime
// dep, even though :transport-http has no UI code. The Karma headless-Chrome runner then
// can't resolve skiko.mjs and the test crashes. JVM tests cover the same behavior; the Wasm
// `compileKotlinWasmJs` target still verifies the API surface compiles cleanly across all
// platforms.
tasks.matching {
    it.name == "wasmJsBrowserTest" ||
        it.name == "wasmJsTest" ||
        it.name == "compileTestDevelopmentExecutableKotlinWasmJs" ||
        it.name == "wasmJsTestTestDevelopmentExecutableCompileSync"
}.configureEach {
    enabled = false
}
