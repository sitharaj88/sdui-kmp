plugins {
    id("sdui.compose.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp telemetry seam: SduiTelemetry interface plus a RecordingTelemetry test " +
            "double for asserting renderer/dispatcher events in tests.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":runtime"))
            // Consent-aware telemetry decorator exposes a @Composable factory that reads
            // LocalConsentSource. Pulling compose.runtime here keeps the consent gating in
            // tooling-telemetry rather than poisoning :runtime with a tooling concern.
            // `api` (not `implementation`) because the @Composable annotation appears on
            // the public surface of `rememberConsentAwareTelemetry`.
            api(compose.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Wasm browser test infra tries to resolve Compose/skiko runtime resources through the
// transitive :runtime dep, even though tooling-telemetry contains no UI code. We test on
// JVM + Android instead — the test double is platform-independent Kotlin.
tasks.matching { it.name == "wasmJsBrowserTest" || it.name == "wasmJsTest" }.configureEach {
    enabled = false
}
