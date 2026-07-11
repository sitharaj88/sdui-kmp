package dev.sdui.kmp.studio.server.db

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
 * Issued JWT tracking for Studio editors. Mirrors the sample-server's `SessionStore` pattern.
 * The JWT itself is self-contained, but a row in [EditorSessions] lets the server revoke a
 * still-valid token (logout, manual revocation) without waiting for `exp`.
 */
public object EditorSessionStore {
    /** Default JWT lifetime — kept in sync with [dev.sdui.kmp.studio.server.auth.StudioJwt]. */
    public val DefaultLifetime: Duration = Duration.ofHours(1)

    /** Tuple returned by [issue]. Callers embed [id] as the JWT `jti` claim. */
    public data class IssuedSession(
        public val id: UUID,
        public val issuedAt: Instant,
        public val expiresAt: Instant,
    )

    /** Record a freshly-minted session. */
    public suspend fun issue(editorId: UUID, lifetime: Duration = DefaultLifetime): IssuedSession =
        newSuspendedTransaction {
            val id = UUID.randomUUID()
            val issuedAt = Instant.now()
            val expiresAt = issuedAt.plus(lifetime)
            EditorSessions.insert {
                it[EditorSessions.id] = id
                it[EditorSessions.editorId] = editorId
                it[EditorSessions.issuedAt] = issuedAt
                it[EditorSessions.expiresAt] = expiresAt
            }
            IssuedSession(id, issuedAt, expiresAt)
        }

    /** Mark a session as revoked. Idempotent. */
    public suspend fun revoke(sessionId: UUID) {
        newSuspendedTransaction {
            EditorSessions.update(
                { (EditorSessions.id eq sessionId) and EditorSessions.revokedAt.isNull() },
            ) {
                it[revokedAt] = Instant.now()
            }
        }
    }

    /**
     * Whether the given session is still valid (exists, not expired, not revoked). The JWT
     * verifier already checks the `exp` claim, but a revocation can happen mid-lifetime so the
     * route MUST consult this before trusting the principal.
     */
    public suspend fun isLive(sessionId: UUID): Boolean = newSuspendedTransaction {
        val now = Instant.now()
        EditorSessions
            .selectAll()
            .where { EditorSessions.id eq sessionId }
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                row[EditorSessions.revokedAt] == null && row[EditorSessions.expiresAt].isAfter(now)
            }
            ?: false
    }
}
