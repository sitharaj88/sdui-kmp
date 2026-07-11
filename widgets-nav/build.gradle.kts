plugins {
    id("sdui.compose.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp navigation widgets: TabBar, NavRail, and BottomBar renderers wired to " +
            "Action.Navigate for declarative cross-screen flow.",
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
        // commonTest pulls in `kotlin("test")` from sdui.kmp.library and inherits the `:protocol`
        // classpath transitively via `:runtime` (which exposes it via `api`). No extra deps.
    }
}
