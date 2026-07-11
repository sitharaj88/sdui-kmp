plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.gradle.plugin.kotlin)
    implementation(libs.gradle.plugin.kotlin.serialization)
    implementation(libs.gradle.plugin.kotlin.compose.compiler)
    implementation(libs.gradle.plugin.compose)
    implementation(libs.gradle.plugin.android)
    implementation(libs.gradle.plugin.detekt)
    implementation(libs.gradle.plugin.dokka)
    implementation(libs.gradle.plugin.kover)
}
