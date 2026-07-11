package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.HttpMethod
import kotlinx.serialization.json.JsonObject

/**
 * Runtime seam for executing [dev.sdui.kmp.protocol.Action.Submit] over a transport.
 *
 * `:runtime` is deliberately transport-agnostic: no Ktor dependency, no knowledge of HTTP
 * semantics beyond method/status. `:transport-http` ships a Ktor-backed implementation; host
 * apps that never dispatch Submit may omit the handler entirely.
 *
 * The [idempotencyKey] parameter carries the **resolved** value of
 * [dev.sdui.kmp.protocol.ActionPolicy.idempotencyKey] — the dispatcher reads the
 * `StatePath` against its [StateStore] and passes the extracted string here. The handler is
 * intentionally not handed the path or the store; this keeps the transport layer free of
 * state-store semantics.
 *
 * Transports that speak HTTP should propagate a non-null key as `X-Idempotency-Key` so the
 * server can deduplicate retries. Transports that do not have a natural slot for it may
 * ignore the value.
 *
 * Note: the abstract method below cannot carry a default value because Kotlin disallows
 * defaults on `fun interface` SAMs. Callers that don't have a key pass `null` explicitly;
 * the [submit] extension below provides the source-compat ergonomics of a default by
 * forwarding to the four-arg form with `idempotencyKey = null`.
 */
public fun interface SubmitHandler {
    public suspend fun submit(
        endpoint: String,
        method: HttpMethod,
        payload: JsonObject,
        idempotencyKey: String?,
    ): SubmitResult
}

/**
 * Source-compat sugar for callers that never carry an idempotency key. Equivalent to
 * invoking [SubmitHandler.submit] with `idempotencyKey = null`.
 */
public suspend fun SubmitHandler.submit(
    endpoint: String,
    method: HttpMethod,
    payload: JsonObject,
): SubmitResult = submit(endpoint, method, payload, idempotencyKey = null)

/** Coarse-grained submit outcome. M3 ships just success/failure; M5 adds conflict + offline queue. */
public sealed interface SubmitResult {
    public data object Success : SubmitResult
    public data class Failure(public val statusCode: Int? = null, public val cause: Throwable? = null) : SubmitResult
}
