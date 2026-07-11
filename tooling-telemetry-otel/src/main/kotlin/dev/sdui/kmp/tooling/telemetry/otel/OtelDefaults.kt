package dev.sdui.kmp.tooling.telemetry.otel

/**
 * Centralised constants used by [OpenTelemetryTelemetry] and any host code that wants to
 * mirror the same instrumentation-scope name (e.g. when filtering on `instrumentation.scope.name`
 * in a backend query).
 */
public object OtelDefaults {
    /**
     * The OpenTelemetry instrumentation-scope name. Backends surface this as
     * `instrumentation.scope.name` on every emitted span / metric / log; using a stable
     * value here means dashboards keep working across SDK upgrades.
     */
    public const val INSTRUMENTATION_NAME: String = "dev.sdui.kmp"

    /**
     * Instrumentation-scope version. Bumped alongside framework releases so backends can
     * disambiguate when behaviour changes between versions of the adapter.
     */
    public const val INSTRUMENTATION_VERSION: String = "1.0.0"
}
