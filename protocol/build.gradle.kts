plugins {
    id("sdui.kmp.module")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp wire protocol: typed, versioned, kotlinx.serialization-backed sealed " +
            "hierarchies for nodes, values, actions, and tokens shared by server and client.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.collections.immutable)
        }
        commonTest.dependencies {
            // kotest-property only — `checkAll { ... }` blocks invoked from inside plain
            // kotlin.test `@Test` functions. We deliberately avoid kotest-runner /
            // kotest-junit; see ADR-0014 for the rationale.
            implementation(libs.kotest.property)
            // `checkAll` is a `suspend fun`. `kotlinx-coroutines-test`'s `runTest` bridges
            // the suspend boundary on every KMP target without requiring `runBlocking`,
            // which doesn't exist on Wasm. Test-only — does not affect the protocol's
            // production dependency surface.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
