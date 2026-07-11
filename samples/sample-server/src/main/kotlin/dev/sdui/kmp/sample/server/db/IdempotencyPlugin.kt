package dev.sdui.kmp.sample.server.db

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

/**
 * Header read from the inbound request to identify a retryable operation.
 */
public const val IDEMPOTENCY_HEADER: String = "X-Idempotency-Key"

/**
 * Marker we set on the outbound response so callers can detect a replay.
 */
public const val IDEMPOTENCY_REPLAYED_HEADER: String = "X-Idempotency-Replayed"

private const val SUBJECT_ANONYMOUS: String = "anonymous"

private data class CacheKey(val key: String, val subject: String, val endpoint: String)

private val cacheKeyAttr = AttributeKey<CacheKey>("sdui.idempotency.cache.key")
private val replayedAttr = AttributeKey<Boolean>("sdui.idempotency.replayed")

/**
 * Ktor plugin: intercepts requests carrying an `X-Idempotency-Key` header, replays the cached
 * response if one exists for the `(key, subject, endpoint)` triple, and otherwise stores the
 * fresh response so the next replay will hit.
 *
 * Only `POST` / `PUT` / `PATCH` requests are eligible — `GET` / `HEAD` are already idempotent
 * and cache support there belongs to HTTP, not the application layer.
 *
 * Implementation:
 *
 *  - [onCall] — runs in the call pipeline before routing. Looks up the cache. On a hit it
 *    `respondText`s the cached body, which commits the response and short-circuits routing.
 *  - [onCallRespond] — runs as the route's response is being assembled. Inspects the
 *    object the route handed to `respond(...)`; if it's a `String` we serialise it
 *    verbatim and cache that, otherwise we fall back to JSON encoding the value via
 *    `kotlinx.serialization.json` (paired with the `ContentNegotiation` plugin which is
 *    already installed in the sample module).
 *
 * The hand-rolled serialisation is fine for the sample's narrow set of response shapes
 * (`{"token": ...}`, `{"error": ...}`, screen JSON via `respond(Screen)`). Real production
 * deployments would inject a serializer factory and treat the return-type variance up front.
 */
public val IdempotencyPlugin: ApplicationPlugin<Unit> =
    createApplicationPlugin(name = "IdempotencyPlugin") {
        val logger = LoggerFactory.getLogger("IdempotencyPlugin")

        // 1) Lookup-and-replay before routing.
        onCall { call ->
            val method = call.request.local.method
            if (method != HttpMethod.Post && method != HttpMethod.Put && method != HttpMethod.Patch) return@onCall
            val key = call.request.headers[IDEMPOTENCY_HEADER] ?: return@onCall
            val subject = call.principal<JWTPrincipal>()?.payload?.subject ?: SUBJECT_ANONYMOUS
            val endpoint = call.request.local.uri
            val cached = try {
                IdempotencyStore.lookup(key = key, subject = subject, endpoint = endpoint)
            } catch (t: Throwable) {
                logger.warn("idempotency lookup failed: {}", t.message)
                null
            }
            if (cached != null) {
                logger.info(
                    "idempotency hit: key={} subject={} endpoint={}",
                    key,
                    subject,
                    endpoint,
                )
                call.attributes.put(replayedAttr, true)
                call.response.headers.append(IDEMPOTENCY_REPLAYED_HEADER, "true")
                call.respondText(cached, ContentType.Application.Json)
                return@onCall
            }
            // Miss: stash the cache key so the response-side hook can store the body.
            call.attributes.put(cacheKeyAttr, CacheKey(key, subject, endpoint))
        }

        // 2) Capture and persist the response body. Runs in the response pipeline; receives
        //    the object the route passed to `call.respond(...)`. We render it to JSON and
        //    persist that. Replays are skipped (their body is the cached body verbatim).
        onCallRespond { call, body ->
            if (call.attributes.getOrNull(replayedAttr) == true) return@onCallRespond
            val cacheKey = call.attributes.getOrNull(cacheKeyAttr) ?: return@onCallRespond
            val rendered = renderBody(body) ?: return@onCallRespond
            try {
                IdempotencyStore.store(
                    key = cacheKey.key,
                    subject = cacheKey.subject,
                    endpoint = cacheKey.endpoint,
                    responseBody = rendered,
                )
            } catch (t: Throwable) {
                logger.warn("idempotency store failed: {}", t.message)
            }
        }
    }

/**
 * Render the route's response object to a JSON string for caching. Returns null for shapes
 * we don't know how to serialise — the route runs normally; only caching is skipped.
 *
 * Ktor's response pipeline transforms common shapes before [io.ktor.server.application.OnCallRespondContext]
 * sees them: `respondText("...")` becomes `TextContent`, and `respond(domainObject)` becomes
 * either `OutgoingContent` (after content-negotiation) or stays as the raw type depending on
 * plugin phase. We handle each common case here.
 */
private fun renderBody(body: Any?): String? = when (body) {
    null -> null
    is String -> body
    is io.ktor.http.content.TextContent -> body.text
    is io.ktor.http.content.OutgoingContent.ByteArrayContent -> String(body.bytes(), Charsets.UTF_8)
    else -> try {
        // Best-effort generic JSON encoding for `respond(domainObject)` cases — works for
        // any class with a kotlinx-serialization @Serializable annotation (e.g. Screen).
        dev.sdui.kmp.protocol.SduiJson.encodeToString(
            kotlinx.serialization.serializer(body.javaClass),
            body,
        )
    } catch (_: Throwable) {
        null
    }
}
