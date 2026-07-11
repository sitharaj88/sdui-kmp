import dev.sdui.kmp.buildlogic.SduiPublishExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.dokka.gradle.DokkaTask

/**
 * Convention plugin: applies `maven-publish` + `signing` + Dokka-backed Javadoc JAR generation
 * to every shipping library module.
 *
 * Reads coordinates from gradle.properties (`sduiGroupId`, `sduiVersion`) with sensible
 * defaults (`dev.sdui.kmp`, `1.0.0-SNAPSHOT`) so it works out-of-the-box on a fresh checkout.
 *
 * Modules expose per-module description via the `sduiPublish { description.set("...") }` DSL
 * — see [SduiPublishExtension].
 *
 * Signing is wired only when `signing.keyId` is present (Sonatype release flow); without it,
 * `publishToMavenLocal` runs unsigned. The Sonatype Central staging repository is wired only
 * when `OSSRH_USERNAME` / `OSSRH_PASSWORD` env vars (or `mavenCentral.username` /
 * `mavenCentral.password` properties) are present. Both behaviors keep local development
 * friction-free.
 */

plugins {
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
}

// --- Coordinates -----------------------------------------------------------------------------

group = (project.findProperty("sduiGroupId") as? String) ?: "dev.sdui.kmp"
version = (project.findProperty("sduiVersion") as? String) ?: "1.0.0-SNAPSHOT"

// --- Per-module DSL --------------------------------------------------------------------------

private val sduiPublish: SduiPublishExtension =
    extensions.create("sduiPublish", SduiPublishExtension::class.java).apply {
        description.convention("sdui-kmp module: ${project.name}")
    }

// --- Javadoc JAR (Dokka-generated) -----------------------------------------------------------
//
// Maven Central requires a `*-javadoc.jar` for every publication. We generate one from Dokka's
// HTML output (Dokka 1.9.20 ships a `dokkaJavadoc` task that emits Javadoc-style HTML). The
// jar is attached to every MavenPublication the kotlin-multiplatform plugin creates.
private val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    group = "documentation"
    description = "Assembles a Javadoc JAR built from Dokka's HTML output for Maven Central."
    archiveClassifier.set("javadoc")
    val dokkaTask = tasks.named("dokkaHtml", DokkaTask::class.java)
    dependsOn(dokkaTask)
    from(dokkaTask.flatMap { it.outputDirectory })
}

// --- KMP-aware sources JAR -------------------------------------------------------------------
//
// For Kotlin/Multiplatform modules, the kotlin plugin already creates per-target sources JARs
// when its publication is wired up — nothing to do. For pure-JVM modules we need to opt the
// Java plugin into emitting sources, since `java-library` is the foundation for those.
plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.findByType(JavaPluginExtension::class.java)?.withSourcesJar()
}

// --- Android library variant publishing ------------------------------------------------------
//
// The Android Gradle Plugin only creates a Maven publication for an Android library when the
// project explicitly opts in. KMP modules that target Android need this so the
// `<module>-androidRelease-…` artifacts land in the local repo. We pick the `release` variant
// (the standard production build) and ask AGP to bundle sources alongside the AAR.
plugins.withId("com.android.library") {
    extensions.configure(com.android.build.api.dsl.LibraryExtension::class.java) {
        publishing {
            singleVariant("release") {
                withSourcesJar()
            }
        }
    }
}

// --- Pure-JVM publication --------------------------------------------------------------------
//
// JVM modules expose a single `release` MavenPublication built from `components["java"]`.
// KMP modules already get per-target publications from the multiplatform plugin; for those
// we only attach POM + Javadoc JAR below. We register the `release` publication only after
// the Java plugin is applied so `components["java"]` is available.
plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure(PublishingExtension::class.java) {
        if (publications.findByName("release") == null) {
            publications.register("release", MavenPublication::class.java) {
                from(components["java"])
            }
        }
    }
}

// --- POM metadata + Javadoc JAR for every publication ----------------------------------------
//
// Configured lazily through `configureEach` on the publications container. The block runs
// for each publication exactly once, when Gradle realises it — late enough that the Kotlin
// multiplatform plugin has already finished its `FinaliseDsl` stage and added its KMP +
// per-target publications.
//
// We deliberately do NOT inspect `artifacts` inside the action: doing so on a KMP publication
// forces `KotlinSoftwareComponent.getUsages()` and racing it with the Kotlin plugin's own
// configuration tripped `IllegalLifecycleException` in earlier versions of this script. The
// kotlin-multiplatform plugin does not currently emit a `javadoc` artifact, so attaching ours
// unconditionally is safe.
extensions.configure(PublishingExtension::class.java) {
    publications.withType(MavenPublication::class.java).configureEach {
        artifact(dokkaJavadocJar)

        pom {
            name.set(project.name)
            description.set(sduiPublish.description)
            url.set("https://github.com/sdui-kmp/sdui-kmp")
            inceptionYear.set("2026")

            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("sdui-kmp")
                    name.set("sdui-kmp authors")
                    url.set("https://github.com/sdui-kmp")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/sdui-kmp/sdui-kmp.git")
                developerConnection.set("scm:git:ssh://git@github.com/sdui-kmp/sdui-kmp.git")
                url.set("https://github.com/sdui-kmp/sdui-kmp")
            }
        }
    }

    // --- Sonatype Central staging repo (opt-in) ----------------------------------------------
    //
    // Wire the OSSRH staging repo only when credentials are present. Local development
    // (`publishToMavenLocal`) does not need it, and adding it unconditionally would make every
    // `publish` task fail without env vars set.
    val ossrhUser = (System.getenv("OSSRH_USERNAME") ?: project.findProperty("mavenCentral.username")) as? String
    val ossrhPass = (System.getenv("OSSRH_PASSWORD") ?: project.findProperty("mavenCentral.password")) as? String
    if (!ossrhUser.isNullOrBlank() && !ossrhPass.isNullOrBlank()) {
        repositories.maven {
            name = "sonatypeCentral"
            // Sonatype Central Portal staging endpoint. Releases are promoted via the web UI
            // (or the `gradle-nexus-publish-plugin` — deferred to a follow-up phase).
            val isSnapshot = version.toString().endsWith("SNAPSHOT")
            url = if (isSnapshot) {
                uri("https://central.sonatype.com/repository/maven-snapshots/")
            } else {
                uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            }
            credentials {
                username = ossrhUser
                password = ossrhPass
            }
        }
    }
}

// --- Signing (opt-in) -----------------------------------------------------------------------
//
// Two paths are supported:
//
//   1. In-memory key (gradle-nexus-publish-plugin v2 convention): the GitHub Actions release
//      workflow injects an ASCII-armored key via `ORG_GRADLE_PROJECT_signingInMemoryKey`
//      and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`. No keyId is required because
//      the armored block already encodes which key to use.
//
//   2. Legacy file/keyId path: `signing.keyId` + `signing.key` (or `signing.secretKey`) +
//      `signing.password`. Kept for compatibility with developers' existing
//      `~/.gradle/gradle.properties` and the older release runbook.
//
// If neither is configured, signing is skipped entirely so `publishToMavenLocal` runs
// unsigned on a fresh checkout — local development must not require a GPG key.
//
// Signing must run after publications are realised, which is why we hang the wiring off
// `afterEvaluate` — at evaluation time KMP publications don't yet exist.
afterEvaluate {
    val publishing = extensions.getByType(PublishingExtension::class.java)
    val inMemoryKey = (project.findProperty("signingInMemoryKey") as? String)
        ?: System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey")
    val inMemoryPassword = (project.findProperty("signingInMemoryKeyPassword") as? String)
        ?: System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword")
    val signingKeyId = project.findProperty("signing.keyId") as? String
    val signingKey = project.findProperty("signing.key") as? String
        ?: project.findProperty("signing.secretKey") as? String
    val signingPassword = project.findProperty("signing.password") as? String

    when {
        !inMemoryKey.isNullOrBlank() -> {
            extensions.configure(SigningExtension::class.java) {
                useInMemoryPgpKeys(inMemoryKey, inMemoryPassword.orEmpty())
                sign(publishing.publications)
            }
        }
        !signingKeyId.isNullOrBlank() && !signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank() -> {
            extensions.configure(SigningExtension::class.java) {
                useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                sign(publishing.publications)
            }
        }
        // else: signing skipped — `publishToMavenLocal` still works.
    }
}
