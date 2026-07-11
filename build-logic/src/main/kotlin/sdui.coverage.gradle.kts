/**
 * Convention plugin that enables Kotlin Kover coverage on a library module.
 *
 *  * Applies the `org.jetbrains.kotlinx.kover` plugin so per-module coverage tasks
 *    (`koverHtmlReport`, `koverXmlReport`, `koverVerify`) become available.
 *  * Excludes synthesized serializer / companion classes from the coverage view —
 *    `kotlinx.serialization` generates these and counting them as missed coverage is
 *    not actionable signal.
 *  * Does NOT install a per-module verify rule. The aggregated rule in the root
 *    `build.gradle.kts` is the single source of truth so coverage gates can't drift
 *    away from each other.
 *
 * Applied automatically from `sdui.kmp.library` and `sdui.jvm.library`; no module
 * needs to opt in by hand.
 *
 * See ADR-0013 for the rationale on the chosen coverage floor.
 */

plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            excludes {
                // kotlinx.serialization synthesized companions and serializers.
                classes(
                    "*\$\$serializer",
                    "*\$Companion",
                )
            }
        }
    }
}
