package dev.sdui.kmp.sample.server.db

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

/**
 * Records `Action.Submit` requests that failed an optimistic-concurrency check so a
 * reconciler can merge the conflicting writes asynchronously. The store is intentionally
 * write-only from the request path's point of view — readers (the reconciler service) can
 * query [OptimisticConflicts] directly.
 */
public object ConflictStore {
    /**
     * Insert a new conflict row. Both states are stored as JSON-encoded strings so the schema
     * stays portable across H2 (no `jsonb`) and Postgres.
     */
    public suspend fun record(key: String, submitted: String, current: String) {
        newSuspendedTransaction {
            OptimisticConflicts.insert {
                it[OptimisticConflicts.key] = key
                it[OptimisticConflicts.submittedState] = submitted
                it[OptimisticConflicts.currentState] = current
                it[OptimisticConflicts.createdAt] = Instant.now()
            }
        }
    }
}
