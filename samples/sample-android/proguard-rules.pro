# =============================================================================
# sample-android: app-level ProGuard / R8 rules
# =============================================================================
#
# Library-level rules ride along in each :sdui-kmp library's AAR via
# `consumerProguardFiles` (see gradle/proguard/sdui-consumer-rules.pro). This
# file only needs rules that are specific to the sample app itself.

# Keep the activity entry point reachable. AGP keeps the manifest-listed
# activity by default, but being explicit is harmless and survives a
# refactor that drops the manifest reference.
-keep class dev.sdui.kmp.sample.android.MainActivity { *; }

# Compose-specific rules ride in the Compose libraries' own consumer rules.
# Coil, Ktor, OkHttp likewise. Nothing else needed here.
