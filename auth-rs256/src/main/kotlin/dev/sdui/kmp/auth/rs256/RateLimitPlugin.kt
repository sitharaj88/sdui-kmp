package dev.sdui.kmp.auth.rs256

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory sliding-window rate limiter, scoped to a Ktor [io.ktor.server.routing.Route]
 * so callers can selectively apply it to sensitive endpoints (e.g. `/auth/login`).
 *
 * **Single-instance only.** State lives in a [ConcurrentHashMap] keyed by the result of
 * [RateLimitConfig.keyExtractor]; behind a load balancer with multiple replicas, each
 * instance enforces its own quota and an attacker can multiply their budget by the replica
 * count. For multi-instance deployments, replace this plugin with one backed by a shared
 * store (Redis `INCR`+`EXPIRE`, Memcached, DynamoDB) — provide a `RateLimiterStore` SAM and
 * swap the in-memory implementation for the shared one.
 *
 * On overflow, returns `429 Too Many Requests` with a `Retry-After` header (seconds until the
 * oldest in-window timestamp ages out).
 */
public class RateLimitConfig {
    /** Maximum requests allowed per [windowSeconds] per [keyExtractor]-derived key. */
    public var requestsPerMinute: Int = DEFAULT_REQUESTS_PER_MINUTE

    /** Sliding-window length in seconds. Defaults to 60 (i.e. "per minute"). */
    public var windowSeconds: Long = DEFAULT_WINDOW_SECONDS

    /**
     * Function deriving the rate-limit bucket key from a call. Default: client remote host.
     * Override to bucket per JWT subject, per API key, per tenant, etc.
     */
    public var keyExtractor: (ApplicationCall) -> String = { it.request.origin.remoteHost }

    /** Maximum buckets retained in the in-memory map; oldest evicted on overflow. */
    public var maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS

    public companion object {
        public const val DEFAULT_REQUESTS_PER_MINUTE: Int = 60
        public const val DEFAULT_WINDOW_SECONDS: Long = 60L
        public const val DEFAULT_MAX_TRACKED_KEYS: Int = 10_000
    }
}

/**
 * Ktor plugin: install on a [io.ktor.server.routing.Route] to enforce a per-key request quota.
 *
 * ```
 * route("/auth") {
 *     install(RateLimitPlugin) { requestsPerMinute = 5 }
 *     post("/login") { ... }
 * }
 * ```
 *
 * Implementation notes:
 *
 *  - State is a [ConcurrentHashMap]<String, [ArrayDeque]<Long>>; deques hold request timestamps
 *    for the matching key. On every call we trim entries older than `windowSeconds` and
 *    compare the surviving count to `requestsPerMinute`.
 *  - Bucket eviction is best-effort: when the map exceeds `maxTrackedKeys` we drop the entire
 *    map (simpler than LRU and bounded by the configured cap). For real production use this
 *    deserves a proper eviction policy.
 *  - We deliberately do not return `X-RateLimit-Remaining` headers — they leak quota signal
 *    without adding security value. `Retry-After` on the 429 is sufficient for clients.
 */
public val RateLimitPlugin: RouteScopedPlugin<RateLimitConfig> =
    createRouteScopedPlugin(name = "RateLimitPlugin", createConfiguration = ::RateLimitConfig) {
        val cfg = pluginConfig
        val windowMs = cfg.windowSeconds * MILLIS_PER_SECOND
        val limit = cfg.requestsPerMinute
        val keyExtractor = cfg.keyExtractor
        val maxKeys = cfg.maxTrackedKeys
        val buckets = ConcurrentHashMap<String, ArrayDeque<Long>>()

        // Installed via [ShortCircuitPhase] rather than `onCall` so a 429 actually halts the
        // pipeline. `onCall` responds but does not stop the matched route handler from running,
        // which on a sensitive endpoint (e.g. `/auth/login`) would still fire its side effects on
        // a request the client sees rejected. See [ShortCircuitPhase].
        on(ShortCircuitPhase) { call ->
            // Cheap fuse: when we blow past the configured cap, evict everything. The next
            // bursts re-populate bucket entries immediately.
            if (buckets.size > maxKeys) buckets.clear()

            val key = keyExtractor(call)
            val now = System.currentTimeMillis()
            val cutoff = now - windowMs
            val bucket = buckets.computeIfAbsent(key) { ArrayDeque() }
            val retryAfterSeconds: Long? = synchronized(bucket) {
                while (bucket.peekFirst() != null && bucket.peekFirst() < cutoff) bucket.pollFirst()
                if (bucket.size >= limit) {
                    val oldest = bucket.peekFirst() ?: now
                    ((oldest + windowMs - now) / MILLIS_PER_SECOND).coerceAtLeast(1L)
                } else {
                    bucket.addLast(now)
                    null
                }
            }
            if (retryAfterSeconds != null) {
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                call.respondText(
                    """{"error":"rate limit exceeded","retry_after_seconds":$retryAfterSeconds}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests,
                )
                // Stop the pipeline: the guarded route handler must not run on a throttled request.
                finish()
            }
        }
    }

private const val MILLIS_PER_SECOND: Long = 1_000L
