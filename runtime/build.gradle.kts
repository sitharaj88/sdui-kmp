plugins {
    id("sdui.compose.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp client runtime: SduiHost, StateStore, NodeRenderer, dispatcher, navigator " +
            "— the Compose Multiplatform engine that turns protocol trees into a live UI.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            api(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
