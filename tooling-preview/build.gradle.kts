plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":runtime"))
    implementation(project(":widgets-core"))
    implementation(project(":widgets-forms"))
    implementation(project(":widgets-media"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.material3)
    implementation(compose.foundation)
}

compose.desktop {
    application {
        mainClass = "dev.sdui.kmp.tooling.preview.MainKt"
        nativeDistributions {
            packageName = "sdui-kmp-preview"
            packageVersion = "1.0.0"
        }
    }
}
