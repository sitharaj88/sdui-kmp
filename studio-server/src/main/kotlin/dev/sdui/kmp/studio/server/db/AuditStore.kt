package dev.sdui.kmp.studio.server.db

import org.jetbrains.exposed.sql.AndOp
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

/** The set of mutations the audit log records. Stored as a wire string in [ScreenAuditLog.action]. */
public enum class AuditAction(public val wire: String) {
    Created("created"),
    Drafted("drafted"),
    Published("published"),
    Reverted("reverted"),
    Deleted("deleted"),
}

/** Read-side projection of an audit row. */
public data class AuditEntry(
    public val id: UUID,
    public val screenId: String,
    public val editorId: UUID,
    public val action: AuditAction,
    public val fromVersion: Int?,
    public val toVersion: Int?,
    public val at: Instant,
    public val requestId: String,
    public val actorIp: String?,
    public val userAgent: String?,
)

/** Filters accepted by `GET /admin/audit`. Any combination is allowed. */
public data class AuditQuery(
    public val screenId: String? = null,
    public val editorId: UUID? = null,
    public val from: Instant? = null,
    public val to: Instant? = null,
    public val limit: Int = DEFAULT_LIMIT,
    public val offset: Long = 0L,
    /**
     * Cursor-based pagination. When set, only rows strictly older than this `at` (or with the
     * same `at` and an id greater than [cursorId]) are returned. Tail of a page becomes the
     * head of the next call: `cursor = "${at.toString()}|${id}"`.
     */
    public val cursorAt: Instant? = null,
    public val cursorId: UUID? = null,
) {
    public companion object {
        public const val DEFAULT_LIMIT: Int = 50
    }
}

/**
 * Append + read access to [ScreenAuditLog]. The table is strictly append-only — every Studio
 * mutation produces exactly one row.
 */
public object AuditStore {
    /** Append a new audit row. */
    @Suppress("LongParameterList")
    public suspend fun append(
        screenId: String,
        editorId: UUID,
        action: AuditAction,
        requestId: String,
        fromVersion: Int? = null,
        toVersion: Int? = null,
        actorIp: String? = null,
        userAgent: String? = null,
    ) {
        newSuspendedTransaction {
            ScreenAuditLog.insert {
                it[ScreenAuditLog.id] = UUID.randomUUID()
                it[ScreenAuditLog.screenId] = screenId
                it[ScreenAuditLog.editorId] = editorId
                it[ScreenAuditLog.action] = action.wire
                it[ScreenAuditLog.fromVersion] = fromVersion
                it[ScreenAuditLog.toVersion] = toVersion
                it[ScreenAuditLog.at] = Instant.now()
                it[ScreenAuditLog.requestId] = requestId
                it[ScreenAuditLog.actorIp] = actorIp
                it[ScreenAuditLog.userAgent] = userAgent
            }
        }
    }

    /** Paginated query. Newest events first. */
    public suspend fun query(filter: AuditQuery): List<AuditEntry> = newSuspendedTransaction {
        val combined = buildPredicate(filter)
        ScreenAuditLog
            .selectAll()
            .where { combined }
            .orderBy(ScreenAuditLog.at, SortOrder.DESC)
            .orderBy(ScreenAuditLog.id, SortOrder.DESC)
            .limit(filter.limit)
            .offset(filter.offset)
            .map(::rowToEntry)
    }

    /**
     * Unbounded streaming projection over the time-window filter. Used by the export route,
     * which writes rows directly to the response without buffering. Callers MUST consume
     * inside the same transaction; we don't materialise a list.
     *
     * Sort is `at ASC, id ASC` — exports are easier to spot-check chronologically than
     * newest-first.
     */
    public suspend fun stream(filter: AuditQuery, action: (AuditEntry) -> Unit) {
        newSuspendedTransaction {
            val combined = buildPredicate(filter)
            ScreenAuditLog
                .selectAll()
                .where { combined }
                .orderBy(ScreenAuditLog.at, SortOrder.ASC)
                .orderBy(ScreenAuditLog.id, SortOrder.ASC)
                .forEach { row -> action(rowToEntry(row)) }
        }
    }

    private fun buildPredicate(filter: AuditQuery): Op<Boolean> {
        val predicates = mutableListOf<Op<Boolean>>()
        filter.screenId?.let { predicates += (ScreenAuditLog.screenId eq it) }
        filter.editorId?.let { predicates += (ScreenAuditLog.editorId eq it) }
        filter.from?.let { predicates += (ScreenAuditLog.at greaterEq it) }
        filter.to?.let { predicates += (ScreenAuditLog.at lessEq it) }
        // Cursor: rows strictly older than (cursorAt, cursorId). DESC ordering means "older"
        // = "smaller at, OR same at and smaller id". Composes with [from]/[to] via AND.
        if (filter.cursorAt != null) {
            val cursorClause: Op<Boolean> = if (filter.cursorId == null) {
                ScreenAuditLog.at less filter.cursorAt
            } else {
                val sameAtSmallerId = (ScreenAuditLog.at eq filter.cursorAt) and
                    (ScreenAuditLog.id less filter.cursorId)
                (ScreenAuditLog.at less filter.cursorAt) or sameAtSmallerId
            }
            predicates += cursorClause
        }
        return if (predicates.isEmpty()) Op.TRUE else AndOp(predicates)
    }

    private fun rowToEntry(row: org.jetbrains.exposed.sql.ResultRow): AuditEntry = AuditEntry(
        id = row[ScreenAuditLog.id],
        screenId = row[ScreenAuditLog.screenId],
        editorId = row[ScreenAuditLog.editorId],
        action = AuditAction.values()
            .firstOrNull { it.wire == row[ScreenAuditLog.action] }
            ?: AuditAction.Drafted,
        fromVersion = row[ScreenAuditLog.fromVersion],
        toVersion = row[ScreenAuditLog.toVersion],
        at = row[ScreenAuditLog.at],
        requestId = row[ScreenAuditLog.requestId],
        actorIp = row[ScreenAuditLog.actorIp],
        userAgent = row[ScreenAuditLog.userAgent],
    )
}
