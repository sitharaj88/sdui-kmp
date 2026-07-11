plugins {
    id("sdui.jvm.library")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp Sentry adapter: JVM-only SduiTelemetry implementation that maps " +
            "renderer / dispatcher events to Sentry breadcrumbs and unknown-node " +
            "fallbacks to Sentry warning events.",
    )
}

dependencies {
    api(project(":runtime"))
    implementation(project(":tooling-telemetry"))

    api(libs.sentry)

    testImplementation(libs.sentry)
}
