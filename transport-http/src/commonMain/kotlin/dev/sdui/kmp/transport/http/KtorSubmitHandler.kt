package dev.sdui.kmp.transport.http

import dev.sdui.kmp.protocol.HttpMethod
import dev.sdui.kmp.runtime.SubmitHandler
import dev.sdui.kmp.runtime.SubmitResult
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod as KtorHttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Wire-level header name carrying the resolved idempotency key. */
public const val IDEMPOTENCY_KEY_HEADER: String = "X-Idempotency-Key"

/**
 * Ktor-backed [SubmitHandler]. Prefixes relative endpoints with [baseUrl]; absolute URLs
 * pass through. Request body is JSON; the [HttpClient] must have a content-negotiation plugin
 * installed (use [installSduiJson]).
 *
 * When the dispatcher resolves a non-null idempotency key, it is forwarded as the
 * [IDEMPOTENCY_KEY_HEADER] header so the server can deduplicate retried submissions.
 */
public class KtorSubmitHandler(
    private val client: HttpClient,
    private val baseUrl: String,
) : SubmitHandler {
    override suspend fun submit(
        endpoint: String,
        method: HttpMethod,
        payload: JsonObject,
        idempotencyKey: String?,
    ): SubmitResult {
        val url = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) endpoint
        else "${baseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
        return try {
            val response = client.request(url) {
                this.method = method.toKtor()
                contentType(ContentType.Application.Json)
                idempotencyKey?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
                setBody(payload)
            }
            if (response.status.isSuccess()) SubmitResult.Success
            else SubmitResult.Failure(statusCode = response.status.value)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            SubmitResult.Failure(cause = t)
        }
    }
}

private fun HttpMethod.toKtor(): KtorHttpMethod = when (this) {
    HttpMethod.Get -> KtorHttpMethod.Get
    HttpMethod.Post -> KtorHttpMethod.Post
    HttpMethod.Put -> KtorHttpMethod.Put
    HttpMethod.Patch -> KtorHttpMethod.Patch
    HttpMethod.Delete -> KtorHttpMethod.Delete
}
