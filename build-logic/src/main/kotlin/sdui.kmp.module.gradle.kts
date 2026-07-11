import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("sdui.kmp.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

private val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val kotlinxSerializationJson =
    versionCatalog.findLibrary("kotlinx-serialization-json").get()

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(kotlinxSerializationJson)
        }
    }
}
