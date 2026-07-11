plugins {
    id("sdui.jvm.library")
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp golden-snapshot harness: deterministic Compose Desktop renderer plus " +
            "JUnit infrastructure for recording and verifying widget pixel baselines.",
    )
}

// `sdui.jvm.library` already enables explicit-API and JVM-17. The Compose
// compiler plugin generates synthetic composable lambdas that don't have
// stable explicit visibility, so we relax to disabled for this tooling
// module — its public surface is small and reviewed by hand.
kotlin {
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
}

dependencies {
    api(project(":protocol"))
    api(project(":runtime"))
    api(project(":widgets-core"))
    api(project(":widgets-forms"))
    api(project(":widgets-media"))

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    // ImageComposeScene and the off-screen Skia surface live in compose.desktop.
    implementation(compose.desktop.currentOs)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    // Compose UI Test infrastructure for the accessibility snapshot suite. The :test source
    // set drives `runComposeUiTest { ... }` and asserts semantic-tree fields per widget; the
    // production pixel-snapshot harness still uses ImageComposeScene only.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(project(":tooling-testing"))
}

// --- snapshot record / verify wiring ----------------------------------------------------------
//
// Two task aliases drive the same JUnit suite under different policies:
//   * `verifyGoldenSnapshots` (the default; hooked into `:tooling-snapshot:check`)
//     runs with `-Droborazzi.test.verify=true` and fails on any pixel diff.
//   * `recordGoldenSnapshots` runs with `-Droborazzi.test.record=true`, regenerating every
//     baseline PNG under `src/test/resources/snapshots/`. Developers commit the result.
//
// We keep the `roborazzi.test.*` property names so that, if we later swap the hand-rolled
// harness for the Roborazzi compose-desktop plugin, no test code or CI invocation has to change.

val recordProp: Provider<Boolean> = providers.gradleProperty("roborazzi.test.record")
    .map { it.toBoolean() }
    .orElse(false)

// Locale, encoding, and timezone are pinned so a developer's machine settings can't
// shift glyph metrics or text shaping between record and verify runs.
fun Test.applyDeterministicJvm() {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("user.timezone", "UTC")
    systemProperty("file.encoding", "UTF-8")
}

tasks.named<Test>("test") {
    val isRecord = recordProp.get()
    systemProperty("roborazzi.test.record", isRecord.toString())
    systemProperty("roborazzi.test.verify", (!isRecord).toString())
    applyDeterministicJvm()
}

val verifyGoldenSnapshots by tasks.registering(Test::class) {
    group = "verification"
    description = "Run the widget snapshot suite in verify mode; fail on any pixel diff."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("roborazzi.test.verify", "true")
    systemProperty("roborazzi.test.record", "false")
    applyDeterministicJvm()
}

val recordGoldenSnapshots by tasks.registering(Test::class) {
    group = "verification"
    description = "Run the widget snapshot suite in record mode; rewrite every baseline PNG."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("roborazzi.test.record", "true")
    systemProperty("roborazzi.test.verify", "false")
    applyDeterministicJvm()
    outputs.upToDateWhen { false } // always re-record when asked
}

tasks.named("check").configure {
    dependsOn(verifyGoldenSnapshots)
}
