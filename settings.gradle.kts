rootProject.name = "sdui-kmp"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS because the Kotlin/Wasm plugin adds its own nodejs repo at project level;
    // the settings-level `exclusiveContent` below satisfies the same resolution so project-level
    // repos are ignored in practice.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Kotlin/Wasm needs Node binaries from nodejs.org; declaring here (not at project level)
        // keeps FAIL_ON_PROJECT_REPOS strict. Filter restricts it to the single group.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist/") {
                    name = "Node Distributions at https://nodejs.org/dist"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("org.nodejs", "node") }
                }
            }
            filter { includeGroup("org.nodejs") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download/") {
                    name = "Yarn Distributions at https://github.com/yarnpkg/yarn/releases"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("com.yarnpkg", "yarn") }
                }
            }
            filter { includeGroup("com.yarnpkg") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Tier 1 — the crown jewel
include(":protocol")
include(":protocol-fixtures")

// Tier 2 — client runtime and server DSL
include(":runtime")
include(":server")

// Tier 3 — widgets
include(":widgets-core")
include(":widgets-forms")
include(":widgets-media")
include(":widgets-media-coil")
include(":widgets-nav")
include(":widgets-native-map")

// Tier 3 — transports
include(":transport-http")
include(":transport-live")
include(":transport-cache")

// Tier 3 — server-side auth helpers
include(":auth-rs256")

// Tier 0 — tooling
include(":tooling-cli")
include(":tooling-preview")
include(":tooling-testing")
include(":tooling-telemetry")
include(":tooling-telemetry-otel")
include(":tooling-telemetry-sentry")
include(":tooling-snapshot")

// Performance / benchmarks
include(":benchmarks")

// Samples
include(":samples:sample-android")
include(":samples:sample-desktop")
include(":samples:sample-wasm")
include(":samples:sample-server")
// :samples:sample-ios produces the SduiSampleShared.framework consumed by the
// hand-authored Xcode project at iosApp/ via embedAndSignAppleFrameworkForXcode.
include(":samples:sample-ios")

// Studio backend — admin REST + Postgres for editor-driven screen authoring.
include(":studio-server")

// Studio — browser admin console for editing screens served by :studio-server.
include(":studio-web")
