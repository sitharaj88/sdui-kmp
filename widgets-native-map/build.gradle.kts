plugins {
    id("sdui.compose.library")
    id("sdui.publish")
    alias(libs.plugins.kotlinSerialization)
}

sduiPublish {
    description.set(
        "sdui-kmp native-map widget: a NativeSurfaceFactory backing the `sdui.map` kind on " +
            "Android (Google Maps Compose) and iOS (MKMapView), with desktop / Wasm fallbacks.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":runtime"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

        androidMain.dependencies {
            // Maps Compose pulls in play-services-maps transitively; declare it explicitly
            // so consumers don't need a second `dependencies` line and so the version is
            // visible in the dependency report.
            implementation(libs.maps.compose)
            implementation(libs.play.services.maps)
        }

        // Shared iOS source set so MKMapView wiring is written once and consumed by every
        // iOS target. Mirrors the pattern used in `:widgets-media-coil`.
        val iosMain by creating {
            dependsOn(commonMain.get())
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosX64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}
