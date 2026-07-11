package dev.sdui.kmp.sample.server.db

import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import org.slf4j.MDC
import java.util.UUID

/**
 * Request-correlation plugin. Honors an inbound `X-Request-Id` header (so a load balancer or
 * gateway-generated id flows through unchanged) and falls back to a server-generated UUID
 * otherwise. The id is:
 *
 *  - placed in SLF4J [MDC] under the `request_id` key, where the JSON encoder picks it up;
 *  - emitted on the outbound response under the same `X-Request-Id` header so the caller can
 *    correlate against its own logs;
 *  - removed from MDC on response-sent so it cannot leak into a thread-pool reuse.
 *
 * MDC propagation across `withContext` / `newSuspendedTransaction` boundaries is handled by
 * `kotlinx-coroutines-slf4j`'s `MDCContext`, which Ktor 3 wires into its dispatcher
 * automatically when `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` is on the classpath.
 */
public val RequestIdPlugin: ApplicationPlugin<Unit> =
    createApplicationPlugin(name = "RequestIdPlugin") {
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

/** SLF4J MDC key used by both the plugin and the JSON encoder. */
public const val MDC_KEY: String = "request_id"
