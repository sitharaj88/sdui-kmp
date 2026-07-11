plugins {
    alias(libs.plugins.androidApplication)
    // Use plain id() — kotlin-gradle-plugin is already on the classpath via build-logic.
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "dev.sdui.kmp.sample.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.sdui.kmp.sample.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        // Hooks the androidx.test runner so `assembleDebugAndroidTest` builds the
        // instrumentation APK from src/androidTest/. Execution is deferred until the
        // project funds an emulator-backed CI lane — see .github/workflows/ci.yml.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Exercise the consumer ProGuard rules every shipping KMP library
            // ships in its AAR (see gradle/proguard/sdui-consumer-rules.pro).
            // Without R8 minification turned on for at least one sample, a
            // missing keep rule on a sealed-hierarchy subclass would silently
            // pass CI and surface only in adopters' release builds.
            isMinifyEnabled = true
            // Use AGP's default optimisation rule set + the rules contributed
            // by every consuming library. Adopters can layer their own rules
            // on top in the same way.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Baseline profile is bundled into the release APK so the
            // benchmark profiler kicks in on first launch. The file is
            // hand-curated for now (see docs/ops/baseline-profiles.md) and
            // can be regenerated via the macrobenchmark module when it is
            // restored on a future Kotlin / AGP upgrade.
            //
            // baselineProfileSrc is a directory; AGP picks up baseline-prof.txt
            // from src/main/ automatically when present, so no extra wiring
            // is required beyond shipping the file in src/main/.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("androidTest") {
            kotlin.srcDir("src/androidTest/kotlin")
        }
    }
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":widgets-core"))
    implementation(project(":widgets-forms"))
    implementation(project(":widgets-media"))
    implementation(project(":widgets-native-map"))
    implementation(project(":transport-http"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    // Demo sign-in handshake (/auth/csrf + /auth/login) builds/parses small JSON payloads.
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)

    // --- Instrumentation harness (compile-only in CI today) -----------------------------
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
