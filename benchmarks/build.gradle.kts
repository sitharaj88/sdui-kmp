plugins {
    id("sdui.jvm.library")
    application
}

application {
    mainClass.set("dev.sdui.kmp.benchmarks.MainKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":runtime"))
    implementation(project(":protocol-fixtures"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
}
