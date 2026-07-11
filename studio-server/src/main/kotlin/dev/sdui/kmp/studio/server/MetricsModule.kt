package dev.sdui.kmp.studio.server

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
 * Process-shared Prometheus registry for the Studio backend. Tests can substitute their own
 * registry by calling [installMetrics] with an explicit instance.
 */
internal val studioMetricsRegistry: PrometheusMeterRegistry =
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

/**
 * Installs the Ktor [MicrometerMetrics] plugin against [registry], plus the standard JVM
 * meter binders (gc, memory, threads, classloader, processor). Mirrors the wiring in
 * sample-server so a single Grafana dashboard can scrape both deployments without
 * label-juggling.
 */
internal fun Application.installMetrics(
    registry: PrometheusMeterRegistry = studioMetricsRegistry,
) {
    install(MicrometerMetrics) {
        this.registry = registry
        meterBinders = listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics(),
        )
        distributionStatisticConfig = io.micrometer.core.instrument.distribution
            .DistributionStatisticConfig.Builder()
            .percentilesHistogram(true)
            .percentiles(0.5, 0.95, 0.99)
            .build()
    }
}

/**
 * Mounts `GET /metrics` returning the Prometheus text-format scrape from [registry].
 */
internal fun Routing.installMetricsRoute(
    registry: PrometheusMeterRegistry = studioMetricsRegistry,
) {
    get("/metrics") {
        call.respondText(
            text = registry.scrape(),
            contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
        )
    }
}
