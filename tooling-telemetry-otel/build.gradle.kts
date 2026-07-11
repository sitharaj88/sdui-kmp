plugins {
    id("sdui.jvm.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp OpenTelemetry adapter: JVM-only SduiTelemetry implementation that emits " +
            "renderer/dispatcher spans through the OpenTelemetry API.",
    )
}

dependencies {
    api(project(":runtime"))
    implementation(project(":tooling-telemetry"))

    api(libs.opentelemetry.api)

    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
}
