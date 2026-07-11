plugins {
    id("sdui.compose.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp core widgets: Compose Multiplatform renderers for Column, Row, Box, " +
            "Text, Button, Spacer, Divider, and LazyList.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":runtime"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
