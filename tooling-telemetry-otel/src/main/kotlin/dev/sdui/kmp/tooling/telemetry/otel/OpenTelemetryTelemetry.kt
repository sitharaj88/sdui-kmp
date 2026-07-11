package dev.sdui.kmp.tooling.telemetry.otel

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.runtime.SduiTelemetry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.trace.SpanKind
import java.util.concurrent.TimeUnit

/**
 * [SduiTelemetry] adapter that forwards every runtime hook to an OpenTelemetry pipeline.
 *
 * Hosts wire one instance into [dev.sdui.kmp.runtime.SduiHost] and every screen render,
 * node render, unknown-node fallback, action dispatch, and binding error becomes an OTel
 * span / metric / log without any per-screen plumbing.
 *
 * Signal mapping (chosen for the right balance of detail vs. cardinality / cost):
 *
 *  * [onScreenRendered] — emits a `sdui.screen.render` span (synthesised from the supplied
 *    `durationMs`, since the runtime measures the duration itself rather than wrapping the
 *    render). Also records a `sdui.screen.render.duration_ms` histogram so dashboards work
 *    even when traces are sampled out.
 *  * [onNodeRendered] — increments the `sdui.node.rendered` counter. Per-node spans would
 *    drown the trace tree, so we keep this purely metric-shaped.
 *  * [onUnknownNode] — emits a WARN log record `sdui.unknown_node` with the node trace, plus
 *    an `sdui.unknown_node.count` counter so on-call alerts fire when the count rises.
 *  * [onActionDispatched] — emits a `sdui.action.dispatch` span keyed by the action's
 *    `@SerialName` (with `simpleName` as a fallback), plus a histogram so action latency is
 *    visible without trace sampling.
 *  * [onBindingError] — WARN log `sdui.binding_error` with the path / expected / got
 *    attributes, plus an `sdui.binding_error.count` counter.
 *
 * Example wiring:
 *
 * ```kotlin
 * val otel = OpenTelemetrySdk.builder()
 *     .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(...).build())
 *     .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(...).build())
 *     .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(...).build())
 *     .build()
 *
 * SduiHost(
 *     screen = screen,
 *     registry = registry,
 *     telemetry = OpenTelemetryTelemetry(otel),
 * )
 * ```
 *
 * Use [usingGlobal] when the host application has already configured
 * [GlobalOpenTelemetry] (e.g. via the OTel Java agent).
 */
public class OpenTelemetryTelemetry(
    openTelemetry: OpenTelemetry,
    instrumentationName: String = OtelDefaults.INSTRUMENTATION_NAME,
    instrumentationVersion: String = OtelDefaults.INSTRUMENTATION_VERSION,
) : SduiTelemetry {

    private val tracer = openTelemetry.getTracer(instrumentationName, instrumentationVersion)
    private val meter = openTelemetry.meterBuilder(instrumentationName)
        .setInstrumentationVersion(instrumentationVersion)
        .build()
    private val logger = openTelemetry.logsBridge
        .loggerBuilder(instrumentationName)
        .setInstrumentationVersion(instrumentationVersion)
        .build()

    private val screenRenderHistogram: DoubleHistogram = meter
        .histogramBuilder("sdui.screen.render.duration_ms")
        .setDescription("Wall-clock time the runtime took to render an SDUI screen tree.")
        .setUnit("ms")
        .build()

    private val nodeRenderedCounter: LongCounter = meter
        .counterBuilder("sdui.node.rendered")
        .setDescription("Count of SDUI nodes the runtime materialised, partitioned by node type.")
        .setUnit("{node}")
        .build()

    private val unknownNodeCounter: LongCounter = meter
        .counterBuilder("sdui.unknown_node.count")
        .setDescription("Count of SDUI nodes whose discriminator was unknown to this client.")
        .setUnit("{node}")
        .build()

    private val actionDispatchHistogram: DoubleHistogram = meter
        .histogramBuilder("sdui.action.dispatch.duration_ms")
        .setDescription("Wall-clock time the runtime took to dispatch an SDUI action.")
        .setUnit("ms")
        .build()

    private val bindingErrorCounter: LongCounter = meter
        .counterBuilder("sdui.binding_error.count")
        .setDescription("Count of state-binding errors raised by the SDUI runtime.")
        .setUnit("{error}")
        .build()

    override fun onScreenRendered(id: ScreenId, version: SchemaVersion, durationMs: Long) {
        val now = System.currentTimeMillis()
        val startNanos = TimeUnit.MILLISECONDS.toNanos(now - durationMs)
        val endNanos = TimeUnit.MILLISECONDS.toNanos(now)
        tracer.spanBuilder("sdui.screen.render")
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
            .setAttribute(SCREEN_ID, id.value)
            .setAttribute(SCHEMA_VERSION, version.value.toLong())
            .startSpan()
            .end(endNanos, TimeUnit.NANOSECONDS)

        screenRenderHistogram.record(
            durationMs.toDouble(),
            Attributes.of(SCREEN_ID, id.value, SCHEMA_VERSION, version.value.toLong()),
        )
    }

    override fun onNodeRendered(type: String, version: SchemaVersion) {
        nodeRenderedCounter.add(
            1L,
            Attributes.of(NODE_TYPE, type, SCHEMA_VERSION, version.value.toLong()),
        )
    }

    override fun onUnknownNode(type: String, trace: List<NodeId>) {
        val traceString = trace.joinToString(separator = "/") { it.value }
        val attrs = Attributes.of(NODE_TYPE, type, NODE_TRACE, traceString)
        unknownNodeCounter.add(1L, attrs)
        logger.logRecordBuilder()
            .setSeverity(Severity.WARN)
            .setSeverityText("WARN")
            .setBody("sdui.unknown_node")
            .setAllAttributes(attrs)
            .emit()
    }

    override fun onActionDispatched(action: Action, durationMs: Long) {
        val now = System.currentTimeMillis()
        val startNanos = TimeUnit.MILLISECONDS.toNanos(now - durationMs)
        val endNanos = TimeUnit.MILLISECONDS.toNanos(now)
        val type = actionDiscriminator(action)
        tracer.spanBuilder("sdui.action.dispatch")
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
            .setAttribute(ACTION_TYPE, type)
            .startSpan()
            .end(endNanos, TimeUnit.NANOSECONDS)

        actionDispatchHistogram.record(
            durationMs.toDouble(),
            Attributes.of(ACTION_TYPE, type),
        )
    }

    override fun onBindingError(path: StatePath, expected: String, got: String) {
        val attrs = Attributes.builder()
            .put(STATE_PATH, path.value)
            .put(EXPECTED_TYPE, expected)
            .put(GOT_TYPE, got)
            .build()
        bindingErrorCounter.add(1L, attrs)
        logger.logRecordBuilder()
            .setSeverity(Severity.WARN)
            .setSeverityText("WARN")
            .setBody("sdui.binding_error")
            .setAllAttributes(attrs)
            .emit()
    }

    private fun actionDiscriminator(action: Action): String {
        // `Action` subtypes are annotated with `@SerialName`, and that name is what shows up
        // on the wire — but reading the annotation requires kotlin-reflect, which we don't
        // want to drag into a tooling module. `simpleName` (e.g. "Navigate", "Submit") is
        // stable, low-cardinality, and human-readable, which is what dashboards want.
        return action::class.simpleName ?: "unknown"
    }

    public companion object {
        private val SCREEN_ID: AttributeKey<String> = AttributeKey.stringKey("sdui.screen.id")
        private val SCHEMA_VERSION: AttributeKey<Long> = AttributeKey.longKey("sdui.schema_version")
        private val NODE_TYPE: AttributeKey<String> = AttributeKey.stringKey("sdui.node.type")
        private val NODE_TRACE: AttributeKey<String> = AttributeKey.stringKey("sdui.node.trace")
        private val ACTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("sdui.action.type")
        private val STATE_PATH: AttributeKey<String> = AttributeKey.stringKey("sdui.state.path")
        private val EXPECTED_TYPE: AttributeKey<String> = AttributeKey.stringKey("sdui.binding.expected")
        private val GOT_TYPE: AttributeKey<String> = AttributeKey.stringKey("sdui.binding.got")

        /**
         * Convenience factory that pulls the [GlobalOpenTelemetry] singleton — appropriate for
         * apps using the OpenTelemetry Java auto-instrumentation agent or a single shared SDK.
         */
        public fun usingGlobal(
            instrumentationName: String = OtelDefaults.INSTRUMENTATION_NAME,
            instrumentationVersion: String = OtelDefaults.INSTRUMENTATION_VERSION,
        ): OpenTelemetryTelemetry = OpenTelemetryTelemetry(
            openTelemetry = GlobalOpenTelemetry.get(),
            instrumentationName = instrumentationName,
            instrumentationVersion = instrumentationVersion,
        )
    }
}
