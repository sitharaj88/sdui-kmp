import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin that wires Detekt 1.23.x into every library module:
 *
 *  * Reads its config from the root `detekt.yml`, layered on top of detekt's defaults
 *    (`buildUponDefaultConfig = true`), so we only override what's project-specific.
 *  * Picks up a per-module `detekt-baseline.xml` if one exists, so existing violations
 *    don't break the build — only NEW issues fail.
 *  * Pulls in the `detekt-formatting` ruleset to give us ktlint-equivalent checks
 *    without a separate ktlint plugin.
 *  * Hooks `:detekt` into `:check` so the standard verification pipeline runs it.
 *
 * Applied automatically from `sdui.kmp.library` and `sdui.jvm.library`; no module
 * needs to opt in by hand.
 */

plugins {
    id("io.gitlab.arturbosch.detekt")
}

private val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val detektVersion: String = versionCatalog.findVersion("detekt").get().requiredVersion

extensions.configure<DetektExtension>("detekt") {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("detekt.yml"))
    val moduleBaseline = project.file("detekt-baseline.xml")
    if (moduleBaseline.exists()) {
        baseline = moduleBaseline
    }
    parallel = true
    ignoreFailures = false
    autoCorrect = false
    basePath = rootProject.projectDir.absolutePath
}

dependencies {
    add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:$detektVersion")
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
    // Detekt's KMP support analyses commonMain etc. via the multiplatform task variants
    // (detektMetadataMain, detektJvmMain, ...). The bare `detekt` task only sees the JVM
    // tree by default; explicitly walk every Kotlin source set we know about.
    exclude("**/build/**", "**/generated/**", "**/resources/**")
}

// Hook a curated subset of Detekt tasks into `check` so the standard verification
// pipeline runs static analysis. We deliberately skip the experimental Android
// variants and per-iOS-target tasks: the KMP "Metadata" + "JvmMain/JvmTest" runs
// already cover commonMain / jvmMain / jvmTest, and Android Lint provides its own
// AGP-native pipeline. This keeps CI walltime down and avoids requiring a full
// Apple toolchain on Linux runners.
val checkDetektTaskNames = setOf(
    "detekt",
    "detektMain",
    "detektTest",
    "detektMetadataMain",
    "detektMetadataCommonMain",
    "detektJvmMain",
    "detektJvmTest",
)

tasks.named("check").configure {
    dependsOn(
        tasks.withType<Detekt>().matching { it.name in checkDetektTaskNames },
    )
}
