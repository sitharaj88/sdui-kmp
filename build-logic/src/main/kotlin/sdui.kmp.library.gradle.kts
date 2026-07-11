import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("sdui.detekt")
    id("sdui.coverage")
}

private val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private fun version(name: String): String = versionCatalog.findVersion(name).get().requiredVersion

kotlin {
    explicitApi()
    jvmToolchain(17)

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // Tell the KMP plugin which Android variants should produce Maven publications.
        // Picks `release` only — `debug` artifacts have no place on Maven Central. Required
        // for the `sdui.publish` convention plugin to emit `*-android-release.aar` artifacts.
        publishLibraryVariants("release")
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Derive the Android namespace from the project name. Hyphens become dots so module
// segments map to package segments (e.g. `widgets-core` → `widgets.core`). Java
// reserved words ("native", "package", ...) collide with package segment syntax, so
// drop the separator entirely for those rather than generating an invalid namespace.
private val javaKeywords = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new", "package",
    "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient",
    "try", "void", "volatile", "while",
)

private fun toAndroidNamespaceSuffix(projectName: String): String {
    val parts = projectName.split('-')
    val sanitized = mutableListOf<String>()
    for (part in parts) {
        if (part.isEmpty()) continue
        if (part in javaKeywords && sanitized.isNotEmpty()) {
            // Glue the keyword onto the preceding segment (`widgets.nativemap`) so it
            // becomes part of an identifier rather than a standalone segment.
            sanitized[sanitized.lastIndex] = sanitized.last() + part
        } else {
            sanitized += part
        }
    }
    return sanitized.joinToString(".")
}

android {
    namespace = "dev.sdui.kmp.${toAndroidNamespaceSuffix(project.name)}"
    compileSdk = version("androidCompileSdk").toInt()
    defaultConfig {
        minSdk = version("androidMinSdk").toInt()
        // Ship a single shared consumer ProGuard / R8 rules file with every
        // KMP-library AAR. Adopters that pull a single :sdui-kmp library
        // automatically pick up the rules required to keep the kotlinx-
        // serialization sealed hierarchies (the heart of the protocol) alive
        // through R8 minification — without this file, server-emitted nodes
        // throw `SerializationException` at runtime in release builds.
        // See gradle/proguard/sdui-consumer-rules.pro for the rule rationale,
        // and docs/ops/runbook.md "R8 / minification" for the operator view.
        consumerProguardFiles(
            rootProject.file("gradle/proguard/sdui-consumer-rules.pro"),
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
