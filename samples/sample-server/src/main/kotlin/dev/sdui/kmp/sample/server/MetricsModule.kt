package dev.sdui.kmp.sample.server

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Process-shared Prometheus registry. Exposed at the application level so the `/metrics`
 * route can scrape it lazily; tests build their own instance per `testApplication` block to
 * keep state isolated.
 */
internal val sampleMetricsRegistry: PrometheusMeterRegistry =
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

/**
 * Installs the Ktor [MicrometerMetrics] plugin against [registry], wiring the standard
 * suite of JVM + system gauges (gc, memory, threads, classloader, processor) plus Ktor's
 * built-in route / status / latency timer. Run this once during application setup.
 *
 * The accompanying scrape endpoint is mounted by [installMetricsRoute].
 */
internal fun Application.installMetrics(
    registry: PrometheusMeterRegistry = sampleMetricsRegistry,
) {
    install(MicrometerMetrics) {
        this.registry = registry
        // Standard JVM observation set. ProcessorMetrics covers /proc/loadavg-style host
        // load when available, ClassLoaderMetrics covers JVM classloader counts. Together
        // they keep the Prometheus dashboard in docs/ops/grafana-dashboard.json populated.
        meterBinders = listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics(),
        )
        // Distribution stats for the request timer. The histogram is what Grafana's
        // p50 / p95 / p99 panels query; without it Prometheus would only expose the
        // sum / count and percentile rules would have nothing to hang off.
        distributionStatisticConfig = io.micrometer.core.instrument.distribution
            .DistributionStatisticConfig.Builder()
            .percentilesHistogram(true)
            .percentiles(0.5, 0.95, 0.99)
            .build()
    }
}

/**
 * Mounts `GET /metrics` returning the Prometheus text-format scrape from [registry]. Pair
 * with [installMetrics]; calling either alone leaves the deployment without a useful
 * scrape pipeline.
 */
internal fun Routing.installMetricsRoute(
    registry: PrometheusMeterRegistry = sampleMetricsRegistry,
) {
    get("/metrics") {
        // Prometheus text-format; the standard "version=0.0.4" content type is the one
        // Prometheus itself negotiates against. Hard-coding it keeps the route stable
        // even if a future Micrometer release flips the default.
        call.respondText(
            text = registry.scrape(),
            contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
        )
    }
}
