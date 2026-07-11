package dev.sdui.kmp.sample.server.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Issued JWT tracking. The JWT itself is self-contained, but a [Sessions] row tracks active
 * issues so the server can revoke a token before its `exp` claim. Revocation is a soft delete
 * — we record `revoked_at` rather than physically deleting the row, so audit trails survive.
 */
public object SessionStore {
    /** Default JWT lifetime — kept in sync with [dev.sdui.kmp.sample.server.SampleJwt]. */
    public val DefaultLifetime: Duration = Duration.ofHours(1)

    /** Tuple returned by [issue]. Callers embed [id] as the JWT `jti` claim. */
    public data class IssuedSession(
        public val id: UUID,
        public val issuedAt: Instant,
        public val expiresAt: Instant,
    )

    /** Record a freshly-minted session. Returns the row's identifiers for JWT claim embedding. */
    public suspend fun issue(subject: String, lifetime: Duration = DefaultLifetime): IssuedSession =
        newSuspendedTransaction {
            val id = UUID.randomUUID()
            val issuedAt = Instant.now()
            val expiresAt = issuedAt.plus(lifetime)
            Sessions.insert {
                it[Sessions.id] = id
                it[Sessions.subject] = subject
                it[Sessions.issuedAt] = issuedAt
                it[Sessions.expiresAt] = expiresAt
            }
            IssuedSession(id, issuedAt, expiresAt)
        }

    /**
     * Mark a session as revoked. Idempotent — repeated calls keep the original `revoked_at`
     * timestamp because the `revoked_at IS NULL` predicate skips already-revoked rows.
     */
    public suspend fun revoke(sessionId: UUID) {
        newSuspendedTransaction {
            Sessions.update({ (Sessions.id eq sessionId) and Sessions.revokedAt.isNull() }) {
                it[Sessions.revokedAt] = Instant.now()
            }
        }
    }

    /**
     * Returns `true` iff [sessionId] has a row that has not been [revoke]d. Used by
     * the JWT verification path to honor server-side revocation immediately, instead of
     * waiting for the JWT's `exp` claim. A missing row also returns `false` so revoking
     * the row + DELETEing it later both produce the same observable behavior.
     */
    public suspend fun isActive(sessionId: UUID): Boolean = newSuspendedTransaction {
        Sessions
            .selectAll()
            .where { (Sessions.id eq sessionId) and Sessions.revokedAt.isNull() }
            .limit(1)
            .any()
    }
}
