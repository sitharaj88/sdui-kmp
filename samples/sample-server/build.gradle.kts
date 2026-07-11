plugins {
    id("sdui.jvm.library")
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("dev.sdui.kmp.sample.server.MainKt")
}

dependencies {
    implementation(project(":server"))
    implementation(project(":auth-rs256"))
    // Live transport JVM publisher: exposes `/live/screens/{id}` so desktop / wasm clients
    // can hot-reload when the studio-server publishes a screen. In this dev wiring the
    // publisher is per-process — see the cross-process note in Main.kt for the gap and
    // pragmatic options (combined app, internal HTTP, Redis pub/sub).
    implementation(project(":transport-live"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    // Ktor MicrometerMetrics plugin + Prometheus registry — exposes a /metrics
    // endpoint in Prometheus text format. JVM (gc / memory / threads / classloader
    // / processor) and Ktor route metrics (status / latency) are wired in
    // installProductionPrelude. See docs/ops/slos.md for the SLO catalogue.
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ktor.serialization.kotlinx.json)
    // M-S6: thin Ktor HTTP client used by StudioAssignmentClient to consult the studio's
    // /screens/{id}/assign endpoint when STUDIO_BASE_URL is set. No new module — the client is
    // one small class in this module. See docs/adr/0018-studio-ab-targeting-model.md
    // §"Sample-server delivery shape".
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.logback.classic)

    // Persistence — Exposed on top of HikariCP. Postgres in prod, H2 in dev fallback.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikari.cp)
    implementation(libs.h2)

    // Coroutine -> SLF4J MDC propagation so request_id survives `withContext` and Exposed's
    // `newSuspendedTransaction` boundaries.
    implementation(libs.kotlinx.coroutines.slf4j)

    // Logback JSON encoder for one-line-per-event structured logs.
    implementation(libs.logback.jackson)
    implementation(libs.logback.json.classic)
    implementation(libs.jackson.databind)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.ktor.client.mock)
}
