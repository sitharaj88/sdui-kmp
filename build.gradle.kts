plugins {
    // Apply plugins to the root without the classpath; subprojects apply convention plugins.
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinComposeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.dokka)
    // gradle-nexus-publish-plugin v2 — drives Sonatype Central staging+release end-to-end so
    // a release tag pushes through `closeAndReleaseSonatypeStagingRepository` instead of the
    // manual web-UI promotion that the legacy OSSRH workflow required. Applied to the ROOT
    // project only; the plugin discovers shipping subprojects via their `publishing` extension.
    alias(libs.plugins.gradleNexusPublish)
    // Kover root aggregator. Subprojects apply Kover via `sdui.coverage` (transitively from
    // `sdui.kmp.library` / `sdui.jvm.library`). The root project uses the plugin for its
    // aggregating reports + verify rule only — it has no Kotlin code of its own.
    // See ADR-0013 for the chosen coverage floor.
    alias(libs.plugins.kover)
}

// --- Sonatype Central staging-and-release wiring ---------------------------------------------
//
// The plugin reads the version on the root project; snapshot vs release routing happens
// automatically based on the `-SNAPSHOT` suffix. The convention plugin sets the same
// coordinates on every shipping module so the version-string check matches everywhere.
//
// Endpoints follow the Central Portal migration documented at
// https://central.sonatype.org/publish/publish-portal-api/ — the legacy `s01.oss.sonatype.org`
// host is sunsetting. Credentials are sourced from environment variables (set by the GitHub
// Actions release workflow) so a fresh checkout without secrets still works for local builds.
group = (project.findProperty("sduiGroupId") as? String) ?: "dev.sdui.kmp"
version = (project.findProperty("sduiVersion") as? String) ?: "1.0.0-SNAPSHOT"

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
            password.set(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
        }
    }
}

// Apply Dokka to every library module so `:dokkaHtmlMultiModule` produces aggregated API
// reference HTML at build/dokka/htmlMultiModule. Sample apps, the preview harness, and the
// benchmarks executable are not part of the public API surface — they're intentionally
// excluded.
val dokkaModules = listOf(
    ":protocol",
    ":protocol-fixtures",
    ":runtime",
    ":server",
    ":widgets-core",
    ":widgets-forms",
    ":widgets-media",
    ":widgets-native-map",
    ":transport-http",
    ":transport-live",
    ":auth-rs256",
    ":tooling-cli",
    ":tooling-telemetry",
    ":tooling-telemetry-sentry",
    ":tooling-testing",
)
configure(dokkaModules.map { project(it) }) {
    apply(plugin = "org.jetbrains.dokka")
}

val protocolSnapshotFile: File = rootDir.resolve("protocol-snapshot.json")

// Writes (or overwrites) the committed baseline. Developers run this locally after deliberate,
// additive protocol changes; the updated file lands in the same PR as the change.
tasks.register<JavaExec>("captureProtocolSnapshot") {
    group = "verification"
    description = "Walk :protocol and write ${protocolSnapshotFile.name} to the repo root."
    dependsOn(":tooling-cli:installDist")
    classpath(provider { project(":tooling-cli").tasks.named("jar").get().outputs.files })
    classpath(provider {
        project(":tooling-cli").configurations.getByName("runtimeClasspath")
    })
    mainClass.set("dev.sdui.kmp.tooling.cli.MainKt")
    args = listOf("capture", protocolSnapshotFile.absolutePath)
}

// Compares current :protocol to the committed baseline. Fails the build on any breaking
// change (removed type / field / enum case, tightened nullability, changed field type or
// discriminator). Hooked into `check` below.
tasks.register<JavaExec>("verifyProtocolSnapshot") {
    group = "verification"
    description = "Compare :protocol to ${protocolSnapshotFile.name}; fail on breaking changes."
    classpath(provider { project(":tooling-cli").tasks.named("jar").get().outputs.files })
    classpath(provider {
        project(":tooling-cli").configurations.getByName("runtimeClasspath")
    })
    mainClass.set("dev.sdui.kmp.tooling.cli.MainKt")
    args = listOf("lint", protocolSnapshotFile.absolutePath)
}

// Hook into `check` in the tooling-cli module so the verification runs as part of the
// standard test pipeline. Using a module-level hook avoids a root-project `check` task.
project(":tooling-cli").afterEvaluate {
    tasks.named("check").configure {
        dependsOn(rootProject.tasks.named("verifyProtocolSnapshot"))
        dependsOn(rootProject.tasks.named("verifyDependencyRules"))
    }
}

// --- publishAllToMavenLocal ----------------------------------------------------------------
//
// Single-shot aggregate that publishes every shipping library module to the local Maven
// repo (~/.m2/repository/dev/sdui/kmp/). Hosts integrating sdui-kmp before a Maven Central
// release just run `./gradlew publishAllToMavenLocal` and add `mavenLocal()` to their
// settings repos. The list mirrors the modules that apply `id("sdui.publish")`; samples,
// :tooling-preview, and :benchmarks are intentionally omitted because they don't ship.
val shippingModules = listOf(
    ":protocol",
    ":protocol-fixtures",
    ":runtime",
    ":server",
    ":widgets-core",
    ":widgets-forms",
    ":widgets-media",
    ":widgets-media-coil",
    ":widgets-nav",
    ":widgets-native-map",
    ":transport-http",
    ":transport-live",
    ":transport-cache",
    ":auth-rs256",
    ":tooling-cli",
    ":tooling-telemetry",
    ":tooling-telemetry-otel",
    ":tooling-telemetry-sentry",
    ":tooling-snapshot",
    ":tooling-testing",
)

tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "Publish every shipping sdui-kmp module to the local Maven repository."
    dependsOn(shippingModules.map { "$it:publishToMavenLocal" })
}

// Ditto for the Sonatype-Central staging repository — only useful when OSSRH credentials
// are present, otherwise individual `:publishAllPublicationsToSonatypeCentralRepository`
// tasks won't exist (the convention plugin gates the repo on credentials).
tasks.register("publishAllToSonatypeCentral") {
    group = "publishing"
    description = "Publish every shipping sdui-kmp module to the Sonatype Central staging repo."
    dependsOn(
        shippingModules.map { "$it:publishAllPublicationsToSonatypeCentralRepository" },
    )
}

// --- verifyRelease ---------------------------------------------------------------------------
//
// Pre-flight check that catches missing Sonatype Central POM metadata BEFORE the release
// workflow tries to push to staging — Sonatype's validation rules will reject any artifact
// missing name / description / url / license / scm / developers, and discovering that in CI
// after a tag push means the staging repo is left dangling and the tag has to be re-cut.
//
// The task:
//   1. Depends on `publishAllToMavenLocal` so every module's POM is materialised on disk.
//   2. Walks `~/.m2/repository/dev/sdui/kmp/<module>/<version>/*.pom` for each shipping
//      module and asserts every required Sonatype field is non-blank.
//   3. Fails fast with a list of all missing fields so a single dry-run surfaces every gap.
//
// Run locally before tagging:    ./gradlew verifyRelease
// Also wired into the release workflow as a fail-fast guard.
tasks.register("verifyRelease") {
    group = "verification"
    description = "Verify every shipping module's published POM has the required Sonatype fields."
    dependsOn("publishAllToMavenLocal")
    // Capture project coordinates eagerly — inside doLast, `group` resolves to the task's
    // own group property (`"verification"`) rather than the Project. Capturing also keeps the
    // task body configuration-cache-friendly.
    val projectGroup = project.group.toString()
    val projectVersion = project.version.toString()
    doLast {
        val groupPath = projectGroup.replace('.', '/')
        val versionStr = projectVersion
        val mavenLocal = file(System.getProperty("user.home")).resolve(".m2/repository")
        val requiredXmlElements = listOf(
            "<name>",
            "<description>",
            "<url>",
            "<licenses>",
            "<scm>",
            "<developers>",
        )

        val problems = mutableListOf<String>()
        shippingModules.forEach { modulePath ->
            val moduleName = modulePath.removePrefix(":")
            val moduleDir = mavenLocal.resolve("$groupPath/$moduleName/$versionStr")
            if (!moduleDir.exists()) {
                problems += "$modulePath: expected POM directory $moduleDir does not exist"
                return@forEach
            }
            val poms = moduleDir.listFiles { f -> f.name.endsWith(".pom") }.orEmpty()
            if (poms.isEmpty()) {
                problems += "$modulePath: no .pom files found under $moduleDir"
                return@forEach
            }
            poms.forEach { pom ->
                val text = pom.readText()
                requiredXmlElements.forEach { element ->
                    if (!text.contains(element)) {
                        problems += "${pom.name} (in $modulePath): missing $element"
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            val msg = buildString {
                appendLine("verifyRelease: ${problems.size} POM metadata problem(s) found:")
                problems.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Sonatype Central rejects releases missing any of: name, description,")
                appendLine("url, licenses, scm, developers. Fix sdui.publish.gradle.kts and re-run.")
            }
            throw GradleException(msg)
        }
        logger.lifecycle(
            "verifyRelease: ${shippingModules.size} modules verified, all POMs include required " +
                "Sonatype Central metadata.",
        )
    }
}

// --- verifyDependencyRules -----------------------------------------------------------------
//
// Enforces the four-tier module dependency rules from ARCHITECTURE.md and CLAUDE.md by
// inspecting the configured project graph after evaluation. No compilation needed — only the
// project-to-project edges declared in production-classpath configurations are walked.
//
// Tier model:
//   PROTOCOL  — :protocol, :protocol-fixtures, :protocol-protobuf
//                  may depend only on kotlinx libs (no project deps allowed).
//   RUNTIME   — :runtime
//   SERVER    — :server
//                  RUNTIME and SERVER may depend on PROTOCOL but never on each other.
//   WIDGET    — :widgets-*
//                  may depend on RUNTIME or PROTOCOL only; never on another widget, never
//                  on a transport, server, sample, or benchmark.
//   TRANSPORT — :transport-*
//                  may depend on RUNTIME or PROTOCOL; never on widgets, server, samples,
//                  or benchmarks.
//   AUTH      — :auth-*
//                  Server-side auth helpers (JWT, JWKS, rate limit, CSRF). Designed to be
//                  reusable across servers — never depends on the sdui protocol/runtime tree.
//   TOOLING   — :tooling-*
//                  may depend on tier-3-and-below (protocol/runtime/server/widgets/transport)
//                  and other tooling; never on samples or benchmarks.
//   SAMPLE    — :samples:*  (no outbound rules)
//   STUDIO    — :studio-web (and any future :studio-* frontend bundle other than :studio-server,
//                  which is server tier)
//                  Browser admin app; consumes runtime + widgets + transport just like a sample,
//                  but lives outside the :samples namespace because it ships separately.
//   BENCHMARK — :benchmarks (nothing else may depend on it; outbound is unconstrained)
//
enum class ModuleTier { PROTOCOL, RUNTIME, SERVER, WIDGET, TRANSPORT, AUTH, TOOLING, SAMPLE, STUDIO, BENCHMARK, UNKNOWN }

fun classifyTier(projectPath: String): ModuleTier {
    // Strip leading ':' and split on ':' to handle nested paths like ':samples:sample-android'.
    val segments = projectPath.removePrefix(":").split(":")
    val head = segments.first()
    return when {
        head == "protocol" || head == "protocol-fixtures" || head == "protocol-protobuf" -> ModuleTier.PROTOCOL
        head == "runtime" -> ModuleTier.RUNTIME
        head == "server" -> ModuleTier.SERVER
        head.startsWith("widgets-") -> ModuleTier.WIDGET
        head.startsWith("transport-") -> ModuleTier.TRANSPORT
        head.startsWith("auth-") -> ModuleTier.AUTH
        head.startsWith("tooling-") -> ModuleTier.TOOLING
        head == "samples" -> ModuleTier.SAMPLE
        // The Studio frontend bundle (:studio-web) is a deployable browser app, not a library.
        // It has the same outbound-rule freedom as a sample (consumes runtime + widgets +
        // transport-http) but is not in the :samples namespace.
        head == "studio-web" -> ModuleTier.STUDIO
        // :studio-server is the JVM admin backend that powers :studio-web. It depends on
        // :protocol + :server + :tooling-cli (the schema linter), so it deliberately reaches
        // across tiers like a deployable application would. Classify it under STUDIO; outbound
        // rules are unconstrained, inbound is locked down so other libs can't depend on it.
        head == "studio-server" -> ModuleTier.STUDIO
        head == "benchmarks" -> ModuleTier.BENCHMARK
        else -> ModuleTier.UNKNOWN
    }
}

data class DepEdge(val from: String, val to: String, val configuration: String)

// Tightly-scoped allow-list of project edges that violate the generic tier rules but are
// architecturally intentional. Each entry must be justified.
val edgeAllowList: Set<Pair<String, String>> = setOf(
    // Fixtures contain canonical sample protocol values; by definition they need protocol types.
    // ARCHITECTURE.md only forbids project deps on :protocol itself, not on :protocol-fixtures.
    ":protocol-fixtures" to ":protocol",
    // Coil-based concrete implementation of the abstract media widget API. The "-coil" suffix
    // marks it as a sibling impl, not a peer widget.
    ":widgets-media-coil" to ":widgets-media",
    // :transport-cache is a cross-cutting persistence helper consumed by HTTP-style transports
    // (and, eventually, the WS transport for last-known-good replay). It exposes only the
    // ScreenDiskCache interface and a per-platform default implementation; it never depends
    // on another transport, so the inter-transport rule is preserved in the other direction.
    ":transport-http" to ":transport-cache",
)

// Returns null if the edge is allowed, or a human-readable reason string if forbidden.
fun violationReason(fromTier: ModuleTier, toTier: ModuleTier, fromPath: String, toPath: String): String? {
    // Self-edges can't actually happen for project deps but guard anyway.
    if (fromPath == toPath) return null
    if ((fromPath to toPath) in edgeAllowList) return null

    return when (fromTier) {
        ModuleTier.PROTOCOL ->
            "tier PROTOCOL ($fromPath) must not depend on any other project; only kotlinx libs allowed"
        ModuleTier.RUNTIME -> when (toTier) {
            ModuleTier.PROTOCOL -> null
            else -> "tier RUNTIME ($fromPath) may only depend on PROTOCOL; got ${toTier.name} ($toPath)"
        }
        ModuleTier.SERVER -> when (toTier) {
            ModuleTier.PROTOCOL -> null
            ModuleTier.RUNTIME -> "tier SERVER ($fromPath) must not depend on RUNTIME ($toPath)"
            else -> "tier SERVER ($fromPath) may only depend on PROTOCOL; got ${toTier.name} ($toPath)"
        }
        ModuleTier.WIDGET -> when (toTier) {
            ModuleTier.RUNTIME, ModuleTier.PROTOCOL -> null
            ModuleTier.WIDGET -> "tier WIDGET ($fromPath) must not depend on another WIDGET ($toPath)"
            else -> "tier WIDGET ($fromPath) may only depend on RUNTIME or PROTOCOL; got ${toTier.name} ($toPath)"
        }
        ModuleTier.TRANSPORT -> when (toTier) {
            ModuleTier.RUNTIME, ModuleTier.PROTOCOL -> null
            ModuleTier.WIDGET -> "tier TRANSPORT ($fromPath) must not depend on WIDGET ($toPath)"
            ModuleTier.SERVER -> "tier TRANSPORT ($fromPath) must not depend on SERVER ($toPath)"
            ModuleTier.TRANSPORT -> "tier TRANSPORT ($fromPath) must not depend on another TRANSPORT ($toPath)"
            else -> "tier TRANSPORT ($fromPath) may only depend on RUNTIME or PROTOCOL; got ${toTier.name} ($toPath)"
        }
        ModuleTier.AUTH ->
            "tier AUTH ($fromPath) must not depend on any other project; only third-party libs allowed"
        ModuleTier.TOOLING -> when (toTier) {
            ModuleTier.SAMPLE -> "tier TOOLING ($fromPath) must not depend on SAMPLE ($toPath)"
            ModuleTier.STUDIO -> "tier TOOLING ($fromPath) must not depend on STUDIO ($toPath)"
            ModuleTier.BENCHMARK -> "tier TOOLING ($fromPath) must not depend on BENCHMARK ($toPath)"
            ModuleTier.UNKNOWN -> "tier TOOLING ($fromPath) depends on UNKNOWN-tier project ($toPath)"
            else -> null
        }
        ModuleTier.SAMPLE -> null // samples may depend on anything
        ModuleTier.STUDIO -> null // studio frontends may depend on anything that ships to clients
        ModuleTier.BENCHMARK -> when (toTier) {
            ModuleTier.SAMPLE -> "tier BENCHMARK ($fromPath) must not depend on SAMPLE ($toPath)"
            ModuleTier.STUDIO -> "tier BENCHMARK ($fromPath) must not depend on STUDIO ($toPath)"
            else -> null
        }
        ModuleTier.UNKNOWN ->
            "consumer ($fromPath) is in UNKNOWN tier — update classifyTier() in root build.gradle.kts"
    }
}

// Production-classpath configuration name predicates. Test classpaths are excluded — a sample
// or test depending on a widget is fine.
fun isProductionConfig(name: String): Boolean {
    if (name.contains("Test", ignoreCase = false)) return false
    // KMP per-target main: e.g. commonMainImplementation, jvmMainApi, iosArm64MainCompileOnly.
    if (name.endsWith("MainImplementation") ||
        name.endsWith("MainApi") ||
        name.endsWith("MainCompileOnly") ||
        name.endsWith("MainRuntimeOnly")) return true
    // Plain JVM / Android library.
    return name == "implementation" || name == "api" || name == "compileOnly" || name == "runtimeOnly"
}

tasks.register("verifyDependencyRules") {
    group = "verification"
    description = "Walk subprojects and assert the four-tier module dependency rules hold."
    doLast {
        val edges = mutableListOf<DepEdge>()
        rootProject.subprojects.forEach { sub ->
            sub.configurations.forEach inner@{ cfg ->
                if (!isProductionConfig(cfg.name)) return@inner
                cfg.dependencies.withType(ProjectDependency::class.java).forEach { dep ->
                    edges += DepEdge(
                        from = sub.path,
                        to = dep.dependencyProject.path,
                        configuration = cfg.name,
                    )
                }
            }
        }

        val violations = edges.mapNotNull { edge ->
            val fromTier = classifyTier(edge.from)
            val toTier = classifyTier(edge.to)
            val reason = violationReason(fromTier, toTier, edge.from, edge.to) ?: return@mapNotNull null
            "  - ${edge.from}  ->  ${edge.to}   (via ${edge.configuration})\n      $reason"
        }.distinct()

        if (violations.isNotEmpty()) {
            val msg = buildString {
                appendLine("verifyDependencyRules: ${violations.size} forbidden project-dependency edge(s):")
                violations.forEach { appendLine(it) }
                appendLine()
                appendLine("Module dependency rules are documented in ARCHITECTURE.md and CLAUDE.md.")
            }
            throw GradleException(msg)
        }
        logger.lifecycle("verifyDependencyRules: ${edges.size} project edges checked, 0 violations.")
    }
}

// --- Kover aggregation + coverage gate ------------------------------------------------------
//
// Subprojects apply Kover via `sdui.coverage` (pulled in by `sdui.kmp.library` /
// `sdui.jvm.library`). The root project aggregates their `koverXmlReport` /
// `koverHtmlReport` outputs and runs the single `koverVerify` rule that gates `check`.
//
// Floor selection: we picked 60% line coverage as the minimum for the merged report.
// This is the existing JVM-tested baseline (~65% on :runtime, ~70% on :protocol, with
// :widgets-* / :transport-* / :tooling-* still unmeasured) minus a ~5pp regression
// budget so coverage refactors don't immediately turn the build red. Tightening the
// floor as new modules join the measured set is a deliberate, separately-reviewed PR.
// See docs/adr/0013-kover-coverage-floor.md for the full rationale.
//
// Modules deliberately excluded from the merged report:
//   * :samples:*          — sample apps are not framework code.
//   * :studio-server, :studio-web — deployable apps, not shipping libraries.
//   * :benchmarks         — JMH harness, no behavior to cover.
//   * :tooling-preview    — IDE-only entry point, no runtime behavior.
val coverageExcludedSubprojects: Set<String> = setOf(
    ":benchmarks",
    ":tooling-preview",
    ":studio-server",
    ":studio-web",
)

dependencies {
    subprojects.forEach { sub ->
        if (sub.path in coverageExcludedSubprojects) return@forEach
        if (sub.path.startsWith(":samples")) return@forEach
        kover(project(sub.path))
    }
}

kover {
    reports {
        verify {
            // Single aggregate gate — see ADR-0013 for the floor rationale.
            rule {
                bound {
                    minValue.set(60)
                }
            }
        }
        filters {
            excludes {
                classes(
                    "*\$\$serializer",
                    "*\$Companion",
                )
            }
        }
    }
}

// Wire `koverVerify` into the standard verification pipeline. We do this on a
// non-existent project hook so a leaf module's `check` doesn't try to run the
// aggregated rule before all sub-reports exist; instead, attach it to the root
// project's lifecycle via an explicit umbrella task.
val coverageGate = tasks.register("coverageGate") {
    group = "verification"
    description = "Run the aggregated Kover verify rule (the merged coverage floor)."
    dependsOn("koverVerify")
}

// Hook into `:tooling-cli:check` so `./gradlew check` (without an explicit project
// path) transitively runs the gate, mirroring how `verifyDependencyRules` and
// `verifyProtocolSnapshot` are wired above.
project(":tooling-cli").afterEvaluate {
    tasks.named("check").configure {
        dependsOn(coverageGate)
    }
}
