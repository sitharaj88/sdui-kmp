plugins {
    id("sdui.compose.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp form widgets: TextField, Checkbox, and the Validation evaluator that " +
            "drive submit-style flows with optimistic updates and rollback.",
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
