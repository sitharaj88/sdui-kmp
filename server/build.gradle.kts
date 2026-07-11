plugins {
    id("sdui.jvm.library")
    alias(libs.plugins.kotlinSerialization)
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp server DSL: type-safe Kotlin builders (`screen { column { text(...) ... } }`) " +
            "that emit protocol trees, ready to serialize with kotlinx.serialization.",
    )
}

dependencies {
    api(project(":protocol"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    testImplementation(libs.kotlinx.serialization.json)
}
