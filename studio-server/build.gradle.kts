plugins {
    id("sdui.jvm.library")
    alias(libs.plugins.kotlinSerialization)
    application
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp Studio backend: admin REST API + Postgres data model that powers an editor " +
            "UI for authoring, versioning, and publishing screens. Validates every draft via " +
            "the schema linter before publish so editors cannot ship a backwards-incompatible tree.",
    )
}

application {
    mainClass.set("dev.sdui.kmp.studio.server.MainKt")
}

dependencies {
    // Tier 2 + Tier 0 dependencies: Studio works on the same Screen DSL the sample-server uses,
    // and validates drafts via the schema linter living in :tooling-cli.
    implementation(project(":protocol"))
    implementation(project(":server"))
    implementation(project(":tooling-cli"))
    // :transport-live's JVM target ships WebSocketLivePublisher + installLiveScreensRoute.
    // Studio depends on it to broadcast LiveEvent.TreePatchEvent on publish via
    // WebSocketPublishNotifier. STUDIO-tier modules have no outbound restriction so this
    // edge is intentional; clients keep consuming the same live transport.
    implementation(project(":transport-live"))
    // :auth-rs256 provides the reusable RateLimitPlugin used to gate the service-to-service
    // /screens/{id}/assign route (audit #6). AUTH-tier helpers are designed for reuse across
    // servers; STUDIO tier has no outbound restriction so this edge passes verifyDependencyRules.
    implementation(project(":auth-rs256"))

    // Ktor stack — same set the sample-server uses, plus HOCON config support.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    // Ktor MicrometerMetrics plugin + Prometheus registry — exposes a /metrics
    // endpoint in Prometheus text format. Wired into studioModule alongside the
    // rest of the production prelude. See docs/ops/slos.md for SLO definitions.
    implementation(libs.ktor.server.metrics.micrometer)
    // StatusPages — uniform ErrorResponse mapping for store-layer + framework exceptions
    // (see StudioStatusPages.kt). Without it, a store race under a unique index surfaces as a
    // bare 500 with a non-JSON body.
    implementation(libs.ktor.server.status.pages)
    // CORS — dev-only allowance so the studio-web dev server (localhost:8082) can call this
    // backend cross-origin. Installed gated on SDUI_ENV in studioModule; never permissive in prod.
    implementation(libs.ktor.server.cors)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    // Persistence — Exposed on top of HikariCP. Postgres in prod, H2 in dev fallback.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikari.cp)
    implementation(libs.h2)

    // Bcrypt — pure-Java password hashing for editor accounts. Stable, zero-dep library.
    implementation(libs.bcrypt)

    // Coroutine -> SLF4J MDC propagation for request_id continuity across `newSuspendedTransaction`.
    implementation(libs.kotlinx.coroutines.slf4j)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}

// Bundle the committed protocol-snapshot.json baseline into the studio's classpath so the
// DraftValidator can run the schema linter at startup without an external file. Avoids
// duplicating the baseline in resources/ (it would drift); we copy from the canonical root
// location at build time.
val copyProtocolSnapshot = tasks.register<Copy>("copyProtocolSnapshot") {
    from(rootProject.layout.projectDirectory.file("protocol-snapshot.json"))
    into(layout.buildDirectory.dir("generated/protocol-snapshot"))
}

sourceSets.named("main") {
    resources.srcDir(copyProtocolSnapshot.map { it.destinationDir })
}

tasks.named("processResources") { dependsOn(copyProtocolSnapshot) }

// Lower bcrypt cost for the unit test suite — production-cost (12) burns ~250 ms per hash and
// adds 5–10 s to a 13-test suite that creates several editor accounts. Cost 4 is fast enough
// (≈10 ms) without changing the algorithm under test.
tasks.named<Test>("test") {
    environment("STUDIO_BCRYPT_COST", "4")
    // Studio JWT is now secure-by-default: StudioJwt.fromEnv() fails fast when STUDIO_JWT_SECRET
    // is unset unless SDUI_ENV names a dev environment. The integration tests boot via
    // studioModule()'s default fromEnv(), so mark the suite as a dev environment (the fail-fast
    // policy itself is covered directly by StudioJwtTest).
    environment("SDUI_ENV", "test")
    // Raise the kotlinx-coroutines-test `runTest` wall-clock ceiling from its 60s default. These
    // are `testApplication` tests on a real clock (bcrypt + HTTP round-trips), run single-forked
    // and — in CI — alongside every other module's `jvmTest` on a 2-core runner. Under that
    // contention a well-behaved test can be starved past 60s and fail with a spurious
    // `UncompletedCoroutinesError`. 5 minutes is ample headroom while still catching a real hang.
    systemProperty("kotlinx.coroutines.test.default_timeout", "5m")
    // Sequentialise across test classes. The H2 in-memory DB is recycled by
    // [StudioTestSupport.resetAndConnect] before each test, but the [StudioDatabase]
    // singleton is process-global — concurrent test classes (Gradle defaults
    // `maxParallelForks` to # CPUs) race each other's `connect()` / `closeAndUnregister()`,
    // tripping `Database does not have any transaction manager`. One fork keeps the suite
    // deterministic at the cost of total wall time.
    maxParallelForks = 1
}
