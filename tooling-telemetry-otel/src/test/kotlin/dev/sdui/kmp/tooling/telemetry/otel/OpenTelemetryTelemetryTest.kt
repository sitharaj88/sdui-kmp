package dev.sdui.kmp.tooling.telemetry.otel

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTelemetryTelemetryTest {

    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var metricReader: InMemoryMetricReader
    private lateinit var logExporter: InMemoryLogRecordExporter
    private lateinit var sdk: OpenTelemetrySdk
    private lateinit var telemetry: OpenTelemetryTelemetry

    @BeforeTest
    fun setUp() {
        spanExporter = InMemorySpanExporter.create()
        metricReader = InMemoryMetricReader.create()
        logExporter = InMemoryLogRecordExporter.create()

        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .setMeterProvider(
                SdkMeterProvider.builder()
                    .registerMetricReader(metricReader)
                    .build(),
            )
            .setLoggerProvider(
                SdkLoggerProvider.builder()
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
                    .build(),
            )
            .build()

        telemetry = OpenTelemetryTelemetry(sdk)
    }

    @AfterTest
    fun tearDown() {
        sdk.close()
    }

    @Test
    fun onScreenRendered_emits_span_and_histogram_with_screen_attributes() {
        telemetry.onScreenRendered(ScreenId("home"), SchemaVersion.V1, durationMs = 42L)

        val spans = spanExporter.finishedSpanItems
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals("sdui.screen.render", span.name)
        assertEquals("home", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.screen.id")))
        assertEquals(1L, span.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("sdui.schema_version")))

        val metric = metricReader.collectAllMetrics().single { it.name == "sdui.screen.render.duration_ms" }
        val point = metric.histogramData.points.single()
        assertEquals(42.0, point.sum)
        assertEquals(1L, point.count)
    }

    @Test
    fun onNodeRendered_increments_counter_with_type_and_version_attributes() {
        telemetry.onNodeRendered("text", SchemaVersion.V1)
        telemetry.onNodeRendered("text", SchemaVersion.V1)
        telemetry.onNodeRendered("button", SchemaVersion.V1)

        val metric = metricReader.collectAllMetrics().single { it.name == "sdui.node.rendered" }
        val byType = metric.longSumData.points.associateBy {
            it.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.node.type"))
        }
        assertEquals(2L, byType.getValue("text").value)
        assertEquals(1L, byType.getValue("button").value)
        assertTrue(spanExporter.finishedSpanItems.isEmpty(), "node renders should not emit spans")
    }

    @Test
    fun onUnknownNode_emits_warn_log_and_increments_counter() {
        telemetry.onUnknownNode("future_widget", listOf(NodeId("root"), NodeId("child")))

        val log = logExporter.finishedLogRecordItems.single()
        assertEquals(Severity.WARN, log.severity)
        assertEquals("sdui.unknown_node", log.bodyValue?.value?.toString())
        assertEquals(
            "future_widget",
            log.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.node.type")),
        )
        assertEquals(
            "root/child",
            log.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.node.trace")),
        )

        val metric = metricReader.collectAllMetrics().single { it.name == "sdui.unknown_node.count" }
        assertEquals(1L, metric.longSumData.points.single().value)
    }

    @Test
    fun onActionDispatched_emits_span_and_histogram_with_action_type() {
        telemetry.onActionDispatched(Action.Navigate(Destination.Back()), durationMs = 7L)

        val span = spanExporter.finishedSpanItems.single()
        assertEquals("sdui.action.dispatch", span.name)
        assertEquals(
            "Navigate",
            span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.action.type")),
        )

        val metric = metricReader.collectAllMetrics().single { it.name == "sdui.action.dispatch.duration_ms" }
        val point = metric.histogramData.points.single()
        assertEquals(7.0, point.sum)
        assertEquals(
            "Navigate",
            point.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.action.type")),
        )
    }

    @Test
    fun onBindingError_emits_warn_log_and_increments_counter_with_path_attributes() {
        telemetry.onBindingError(StatePath("user.name"), expected = "string", got = "number")

        val log = logExporter.finishedLogRecordItems.single()
        assertEquals(Severity.WARN, log.severity)
        assertEquals("sdui.binding_error", log.bodyValue?.value?.toString())
        assertEquals(
            "user.name",
            log.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.state.path")),
        )
        assertEquals(
            "string",
            log.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.binding.expected")),
        )
        assertEquals(
            "number",
            log.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sdui.binding.got")),
        )

        val metric = metricReader.collectAllMetrics().single { it.name == "sdui.binding_error.count" }
        assertEquals(1L, metric.longSumData.points.single().value)
    }

    @Test
    fun instrumentation_scope_uses_default_name_and_version() {
        telemetry.onNodeRendered("text", SchemaVersion.V1)

        val metric = metricReader.collectAllMetrics().single { it.name == "sdui.node.rendered" }
        assertEquals(OtelDefaults.INSTRUMENTATION_NAME, metric.instrumentationScopeInfo.name)
        assertNotNull(metric.instrumentationScopeInfo.version)
    }
}
