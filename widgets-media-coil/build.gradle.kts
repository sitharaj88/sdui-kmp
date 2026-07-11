plugins {
    id("sdui.compose.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp Coil 3 image loader: opt-in concrete ImageLoader implementation backed by " +
            "Coil 3 multiplatform with per-target Ktor 3 network engines.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":widgets-media"))
            implementation(project(":runtime"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)

            // Coil 3 multiplatform — image loader + Compose integration + Ktor 3 network engine.
            // Exposed as `api` because consumers of this opt-in module legitimately need to
            // construct/customize Coil's own ImageLoader, request builders, and PlatformContext.
            api(libs.coil.compose)
            api(libs.coil.network.ktor3)
        }

        // Per-platform Ktor engines for Coil's network fetcher. coil-network-ktor3 needs an
        // engine on every target except JS (Wasm/JS use the browser fetch transparently).
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
            }
        }
        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosX64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}
