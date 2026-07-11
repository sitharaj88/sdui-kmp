plugins {
    id("sdui.jvm.library")
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp accessibility test harness: SemanticsNodeInteraction extensions that assert " +
            "WCAG 2.2 conformance (content description, role, touch-target size) against any " +
            "renderer driven by Compose Multiplatform's ui-test infrastructure.",
    )
}

// `sdui.jvm.library` already enables explicit-API. Compose's generated synthetic lambdas
// don't carry stable visibility, mirroring how :tooling-snapshot relaxes the rule. The
// public surface of this module is small (three Modifier extensions) and reviewed by hand.
kotlin {
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
}

dependencies {
    api(project(":protocol"))
    api(project(":runtime"))

    api(compose.runtime)
    api(compose.foundation)
    api(compose.material3)
    api(compose.ui)
    // Compose UI Test infrastructure: the SemanticsNodeInteraction surface and runComposeUiTest
    // helpers used by adopters' a11y suites. Pulled in as `api` because every consumer of
    // [A11yAssertions] needs the same SemanticsNodeInteraction type on its classpath.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    api(compose.uiTest)

    implementation(libs.kotlinx.coroutines.core)
}
