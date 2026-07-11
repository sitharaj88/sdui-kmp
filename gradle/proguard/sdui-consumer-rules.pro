# =============================================================================
# sdui-kmp consumer ProGuard / R8 rules (shared baseline)
# =============================================================================
#
# Every shipping KMP library applies this file via `consumerProguardFiles(...)`
# in `sdui.kmp.library`. R8 in an adopter's release build automatically merges
# these rules so polymorphic dispatch through kotlinx-serialization sealed
# hierarchies (the heart of the protocol) keeps working after minification.
#
# Without these rules, R8 strips the synthesized `$serializer` companions and
# the sealed-subtype reflection table, which manifests as runtime
# `SerializationException: Serializer for class 'X' is not found` when a
# server-emitted screen lands on a release-mode client.
#
# Reference: https://github.com/Kotlin/kotlinx.serialization#android
# Reference: https://kotlinlang.org/docs/serialization.html#example-jsonbuilder

# --- kotlinx-serialization: keep generated $$serializer + Companion objects ---

# Keep all $$serializer companions emitted by the kotlinx.serialization compiler
# plugin. These are the entry points for both encoding and decoding; without
# them every @Serializable type fails at runtime.
-keepclassmembers class **$$serializer {
    public static ** INSTANCE;
    *** descriptor;
}

# Every @Serializable type carries a synthesized Companion that exposes serializer().
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# The kotlinx.serialization library itself (descriptors, encoders, polymorphic
# helpers). `allowobfuscation` lets R8 still rename internal symbols.
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.KSerializer
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.internal.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
    *** INSTANCE;
}

# --- sdui-kmp protocol: keep the sealed hierarchy + every $$serializer --------
#
# `:protocol` is the wire format. Every UiNode, Action, Value, Destination,
# Predicate, etc. participates in polymorphic decode keyed by `@SerialName`.
# Renaming or stripping any of these types breaks server↔client compatibility.

-keep class dev.sdui.kmp.protocol.** { *; }
-keep class dev.sdui.kmp.protocol.**$Companion { *; }
-keep class dev.sdui.kmp.protocol.**$$serializer { *; }

# --- sdui-kmp widgets: keep widget-tier sealed subclasses + $$serializer ------
#
# Widget modules (`:widgets-core`, `:widgets-forms`, `:widgets-media`,
# `:widgets-nav`, `:widgets-native-map`) ship `@Serializable` UiNode subclasses
# that participate in the same polymorphic decode as `:protocol`. The package
# segment between `dev.sdui.kmp` and the type is stripped of hyphens by the
# convention plugin (`widgets-core` -> `widgetscore`), so the wildcard below
# matches `dev.sdui.kmp.widgetscore.*`, `dev.sdui.kmp.widgetsforms.*`, etc.

-keep class dev.sdui.kmp.widgets** { *; }
-keep class dev.sdui.kmp.widgets**$Companion { *; }
-keep class dev.sdui.kmp.widgets**$$serializer { *; }

# --- Runtime + transports: keep public API for adopters -----------------------
#
# Adopters interact with `SduiHost`, `SduiTelemetry`, `WidgetRegistry`,
# `HttpScreenSource`, etc. by reflection-friendly names (Compose runtime,
# DI containers). Keep everything; the runtime/transport modules are small.

-keep class dev.sdui.kmp.runtime.** { *; }
-keep class dev.sdui.kmp.transport.** { *; }

# --- Tooling-telemetry: SduiTelemetry adapter implementations -----------------
#
# The Sentry / OpenTelemetry adapters override interface methods on
# `SduiTelemetry`. R8 with optimisation enabled occasionally rewrites these
# overrides; keep the public surface so the adapter still receives events.

-keep class dev.sdui.kmp.tooling.telemetry.** { *; }

# --- Defensive catch-all: don't fail on missing kotlinx-serialization classes -
#
# kotlinx-serialization's reflective lookups can resolve classes that R8
# considers dead. `dontwarn` keeps R8 quiet so a missing optional class does
# not break the build.

-dontwarn kotlinx.serialization.**
-dontwarn dev.sdui.kmp.protocol.**
