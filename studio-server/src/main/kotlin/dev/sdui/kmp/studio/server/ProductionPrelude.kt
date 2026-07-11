package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.db.StudioDatabase
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Request-correlation plugin for the Studio control plane. Mirrors the sample-server's plugin
 * so both deployments share one log schema and one dashboard.
 *
 * Honors an inbound [REQUEST_ID_HEADER] (so a load-balancer or gateway-generated id flows
 * through unchanged) and falls back to a server-generated UUID otherwise. The id is:
 *
 *  - placed in SLF4J [MDC] under [MDC_KEY], where the log encoder picks it up;
 *  - echoed on the outbound response under the same header so the caller can correlate;
 *  - removed from MDC on response-sent so it cannot leak into a thread-pool reuse.
 *
 * MDC propagation across `withContext` / `newSuspendedTransaction` boundaries is handled by
 * `kotlinx-coroutines-slf4j`'s `MDCContext`, which is on the studio-server classpath.
 */
public val StudioRequestIdPlugin: ApplicationPlugin<Unit> =
    createApplicationPlugin(name = "StudioRequestIdPlugin") {
        on(CallSetup) { call ->
            val inbound = call.request.headers[REQUEST_ID_HEADER]
            val requestId = if (inbound.isNullOrBlank()) UUID.randomUUID().toString() else inbound
            MDC.put(MDC_KEY, requestId)
            call.response.headers.append(REQUEST_ID_HEADER, requestId)
        }
        on(ResponseSent) { _ ->
            MDC.remove(MDC_KEY)
        }
    }

/** Inbound/outbound correlation header. */
public const val REQUEST_ID_HEADER: String = "X-Request-Id"

/** SLF4J MDC key used by both the plugin and the log encoder pattern (`%X{request_id}`). */
public const val MDC_KEY: String = "request_id"

private val requestLogStartedAt = AttributeKey<Long>("sdui.studio.requestlog.started")
private const val NANOS_PER_MILLI: Long = 1_000_000L

/**
 * One-line-per-request access log at INFO. Captures method, path, status, and duration in
 * milliseconds under the `access` logger. Pairs with [StudioRequestIdPlugin] so each line
 * carries the `request_id` MDC entry.
 */
public val StudioRequestLogPlugin: ApplicationPlugin<Unit> =
    createApplicationPlugin(name = "StudioRequestLogPlugin") {
        val logger = LoggerFactory.getLogger("access")
        on(CallSetup) { call ->
            call.attributes.put(requestLogStartedAt, System.nanoTime())
        }
        on(ResponseSent) { call ->
            val started = call.attributes.getOrNull(requestLogStartedAt) ?: return@on
            val durationMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            val status = call.response.status()?.value ?: 0
            logger.info(
                "{} {} {} {}ms",
                call.request.local.method.value,
                call.request.local.uri,
                status,
                durationMs,
            )
        }
    }

/**
 * Installs the `/readiness` endpoint.
 *
 *  - Returns `200 OK` with `{"ready":true,"fallback_db":<bool>}` when the database
 *    connectivity probe succeeds.
 *  - Returns `503 Service Unavailable` with `{"ready":false,"reasons":[...]}` otherwise.
 *
 * `/health` (defined in [studioModule]) stays trivial and always returns 200 — that is the
 * liveness check. `/readiness` reflects real dependencies and is what k8s / the load balancer
 * should gate traffic on.
 */
public fun Routing.installReadinessRoute() {
    get("/readiness") {
        val dbError = StudioDatabase.probe()
        if (dbError == null) {
            call.respondText(
                """{"ready":true,"fallback_db":${StudioDatabase.isFallback}}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        } else {
            val reason = "db: $dbError".replace("\"", "\\\"")
            call.respondText(
                """{"ready":false,"reasons":["$reason"]}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}
