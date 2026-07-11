plugins {
    id("sdui.jvm.library")
    alias(libs.plugins.kotlinSerialization)
    application
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp command-line tooling: protocol-snapshot capture/lint utility that " +
            "blocks breaking schema changes by walking the :protocol sealed hierarchy.",
    )
}

application {
    mainClass.set("dev.sdui.kmp.tooling.cli.MainKt")
}

dependencies {
    api(project(":protocol"))
    implementation(libs.kotlinx.serialization.json)
}
