package dev.sdui.kmp.sample.server.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Duration
import java.time.Instant

/**
 * Persists cached responses for [dev.sdui.kmp.protocol.Action.Submit]-style requests so that
 * a client retry carrying the same `X-Idempotency-Key` header replays the same response body
 * instead of re-running the side-effect.
 *
 * The composite key is `(key, subject, endpoint)` so the same idempotency string can be reused
 * by the same user across distinct endpoints (and, more importantly, cannot be replayed across
 * subjects — that would be a confused-deputy hole).
 */
public object IdempotencyStore {
    /** Default TTL — 24 hours. Aligns with the table comment in `V1__initial.sql`. */
    public val DefaultTtl: Duration = Duration.ofHours(24)

    /**
     * Look up a cached response. Returns the JSON body verbatim, or `null` if no live entry
     * exists. Expired entries are filtered out (and lazily cleaned on the next [store]).
     */
    public suspend fun lookup(key: String, subject: String, endpoint: String): String? =
        newSuspendedTransaction {
            val now = Instant.now()
            IdempotencyKeys
                .selectAll()
                .where {
                    (IdempotencyKeys.key eq key) and
                        (IdempotencyKeys.subject eq subject) and
                        (IdempotencyKeys.endpoint eq endpoint) and
                        (IdempotencyKeys.expiresAt greater now)
                }
                .limit(1)
                .firstOrNull()
                ?.get(IdempotencyKeys.responseBody)
        }

    /**
     * Insert a fresh cache entry. Lazily reaps any expired row sharing the same composite key
     * before insert so a stale entry never blocks a legitimate retry-after-expiry.
     */
    public suspend fun store(
        key: String,
        subject: String,
        endpoint: String,
        responseBody: String,
        ttl: Duration = DefaultTtl,
    ) {
        newSuspendedTransaction {
            val now = Instant.now()
            IdempotencyKeys.deleteWhere {
                (IdempotencyKeys.key eq key) and
                    (IdempotencyKeys.subject eq subject) and
                    (IdempotencyKeys.endpoint eq endpoint)
            }
            IdempotencyKeys.insert {
                it[IdempotencyKeys.key] = key
                it[IdempotencyKeys.subject] = subject
                it[IdempotencyKeys.endpoint] = endpoint
                it[IdempotencyKeys.responseBody] = responseBody
                it[IdempotencyKeys.createdAt] = now
                it[IdempotencyKeys.expiresAt] = now.plus(ttl)
            }
        }
    }
}
