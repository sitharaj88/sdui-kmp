package dev.sdui.kmp.sample.server.db

import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

private val startedAtAttr = AttributeKey<Long>("sdui.requestlog.started")

/**
 * One-line-per-request access log at INFO. Captures method, path, status, and duration in
 * milliseconds so the JSON encoder emits a structured access record. Pairs with [RequestIdPlugin]
 * to give each line a `request_id` MDC entry.
 */
public val RequestLogPlugin: ApplicationPlugin<Unit> =
    createApplicationPlugin(name = "RequestLogPlugin") {
        val logger = LoggerFactory.getLogger("access")
        on(CallSetup) { call ->
            call.attributes.put(startedAtAttr, System.nanoTime())
        }
        on(ResponseSent) { call ->
            val started = call.attributes.getOrNull(startedAtAttr) ?: return@on
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

private const val NANOS_PER_MILLI: Long = 1_000_000L
