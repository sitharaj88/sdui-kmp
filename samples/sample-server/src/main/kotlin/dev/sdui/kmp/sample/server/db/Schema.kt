package dev.sdui.kmp.sample.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Active JWTs minted by `/auth/login`. The JWT itself is self-contained, but a row here lets
 * the server revoke a still-valid token (logout, manual revocation) without waiting for `exp`.
 *
 * Schema mirrors `samples/sample-server/db/migrations/V1__initial.sql` — keep both in lockstep.
 */
public object Sessions : Table("sessions") {
    /** Server-generated session id, also embedded as the `jti` claim for revocation lookup. */
    public val id: org.jetbrains.exposed.sql.Column<java.util.UUID> = uuid("id")
    public val subject: org.jetbrains.exposed.sql.Column<String> = text("subject")
    public val issuedAt: org.jetbrains.exposed.sql.Column<java.time.Instant> = timestamp("issued_at")
    public val expiresAt: org.jetbrains.exposed.sql.Column<java.time.Instant> = timestamp("expires_at")
    public val revokedAt: org.jetbrains.exposed.sql.Column<java.time.Instant?> = timestamp("revoked_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * Cached responses for `Action.Submit` requests carrying an `X-Idempotency-Key` header. A
 * second request with the same `(key, subject, endpoint)` triple within the TTL replays the
 * stored response body verbatim. 24-hour TTL is the convention for sample workloads; production
 * deployments should size this against expected client retry windows.
 */
public object IdempotencyKeys : Table("idempotency_keys") {
    public val key: org.jetbrains.exposed.sql.Column<String> = text("key")
    public val subject: org.jetbrains.exposed.sql.Column<String> = text("subject")
    public val endpoint: org.jetbrains.exposed.sql.Column<String> = text("endpoint")
    public val responseBody: org.jetbrains.exposed.sql.Column<String> = text("response_body")
    public val createdAt: org.jetbrains.exposed.sql.Column<java.time.Instant> = timestamp("created_at")
    public val expiresAt: org.jetbrains.exposed.sql.Column<java.time.Instant> = timestamp("expires_at")

    /**
     * Composite PK: same client key against a different endpoint is a different operation,
     * and same key across users must not cross-replay (avoids a trivial confused-deputy).
     */
    override val primaryKey: PrimaryKey = PrimaryKey(key, subject, endpoint)
}

/**
 * Records `Action.Submit` requests that arrived with an out-of-date `If-Match` ETag (or its
 * equivalent — we use the idempotency key for now). Reconcilers consume these later to merge
 * conflicting writes. `submitted_state` and `current_state` are stored as TEXT (JSON-encoded)
 * to keep the schema portable across H2 (no native jsonb) and Postgres; production deploys can
 * upgrade these columns to `jsonb` via a follow-up migration.
 */
public object OptimisticConflicts : Table("optimistic_conflicts") {
    public val key: org.jetbrains.exposed.sql.Column<String> = text("key")
    public val submittedState: org.jetbrains.exposed.sql.Column<String> = text("submitted_state")
    public val currentState: org.jetbrains.exposed.sql.Column<String> = text("current_state")
    public val createdAt: org.jetbrains.exposed.sql.Column<java.time.Instant> = timestamp("created_at")
    public val resolvedAt: org.jetbrains.exposed.sql.Column<java.time.Instant?> = timestamp("resolved_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(key)
}
