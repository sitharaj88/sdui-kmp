package dev.sdui.kmp.studio.server.db

import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/** Read-side projection of a published screen version row. */
public data class ScreenVersionRow(
    public val id: UUID,
    public val screenId: String,
    public val versionNumber: Int,
    public val bodyJson: String,
    public val editorId: UUID,
    public val createdAt: Instant,
    public val publishedAt: Instant?,
)

/** Listing summary returned by `GET /admin/screens`. */
public data class ScreenSummary(
    public val screenId: String,
    public val currentVersion: Int?,
    public val updatedAt: Instant,
    public val hasDraft: Boolean,
)

/** Read-side projection of a draft row. */
public data class ScreenDraftRow(
    public val id: UUID,
    public val screenId: String,
    public val bodyJson: String,
    public val editorId: UUID,
    public val createdAt: Instant,
    public val updatedAt: Instant,
)

/**
 * Combined CRUD + history surface for screens. Wraps the four tables that move together
 * ([ScreenDefinitions], [ScreenVersions], [ScreenDrafts], [ScreenAuditLog]) so route handlers
 * can express each business operation as a single suspend call.
 */
@Suppress("TooManyFunctions")
public object ScreenStore {

    /**
     * List every non-deleted screen, with a flag for whether the calling editor has a draft.
     *
     * The current version number is resolved by a LEFT join from [ScreenDefinitions] onto the
     * single [ScreenVersions] row referenced by `current_version_id`, projecting ONLY the columns
     * the summary needs. This never reads the `body_json` blob of any version, and never scans the
     * full append-only version history — cost stays proportional to the number of live screens,
     * not to the lifetime count of published versions. (A screen created but never published has a
     * null `current_version_id`, so the outer join yields a null version number.)
     */
    public suspend fun listScreens(forEditor: UUID): List<ScreenSummary> = newSuspendedTransaction {
        val draftScreenIds = ScreenDrafts
            .select(ScreenDrafts.screenId)
            .where { ScreenDrafts.editorId eq forEditor }
            .map { it[ScreenDrafts.screenId] }
            .toSet()
        ScreenDefinitions
            .join(
                ScreenVersions,
                JoinType.LEFT,
                onColumn = ScreenDefinitions.currentVersionId,
                otherColumn = ScreenVersions.id,
            )
            .select(
                ScreenDefinitions.screenId,
                ScreenDefinitions.updatedAt,
                ScreenVersions.versionNumber,
            )
            .where { ScreenDefinitions.deletedAt.isNull() }
            .map { row ->
                val screenId = row[ScreenDefinitions.screenId]
                ScreenSummary(
                    screenId = screenId,
                    currentVersion = row.getOrNull(ScreenVersions.versionNumber),
                    updatedAt = row[ScreenDefinitions.updatedAt],
                    hasDraft = screenId in draftScreenIds,
                )
            }
    }

    /** Fetch the current published version of a screen, or null if none / soft-deleted. */
    public suspend fun currentVersion(screenId: String): ScreenVersionRow? = newSuspendedTransaction {
        val def = ScreenDefinitions
            .selectAll()
            .where { (ScreenDefinitions.screenId eq screenId) and ScreenDefinitions.deletedAt.isNull() }
            .limit(1)
            .firstOrNull() ?: return@newSuspendedTransaction null
        val currentVersionId = def[ScreenDefinitions.currentVersionId] ?: return@newSuspendedTransaction null
        ScreenVersions
            .selectAll()
            .where { ScreenVersions.id eq currentVersionId }
            .limit(1)
            .firstOrNull()
            ?.toVersionRow()
    }

    /** Paginated version history for a screen, newest first. */
    public suspend fun versionHistory(screenId: String, limit: Int, offset: Long): List<ScreenVersionRow> =
        newSuspendedTransaction {
            ScreenVersions
                .selectAll()
                .where { ScreenVersions.screenId eq screenId }
                .orderBy(ScreenVersions.versionNumber, SortOrder.DESC)
                .limit(limit)
                .offset(offset)
                .map { it.toVersionRow() }
        }

    /** Fetch a specific historical version by number. */
    public suspend fun versionByNumber(screenId: String, versionNumber: Int): ScreenVersionRow? =
        newSuspendedTransaction {
            ScreenVersions
                .selectAll()
                .where {
                    (ScreenVersions.screenId eq screenId) and
                        (ScreenVersions.versionNumber eq versionNumber)
                }
                .limit(1)
                .firstOrNull()
                ?.toVersionRow()
        }

    /** Fetch a version by its row UUID — used by the experiments layer to resolve a variant. */
    public suspend fun versionById(versionId: UUID): ScreenVersionRow? = newSuspendedTransaction {
        ScreenVersions
            .selectAll()
            .where { ScreenVersions.id eq versionId }
            .limit(1)
            .firstOrNull()
            ?.toVersionRow()
    }

    /**
     * Promote a specific historical version to be the current published version. Used by the
     * experiments layer's "promote variant" admin action, which short-circuits the normal
     * draft → publish pipeline so an editor can ship the winning variant in one click.
     */
    public suspend fun setCurrentVersion(screenId: String, versionId: UUID): Boolean = newSuspendedTransaction {
        val versionExists = ScreenVersions
            .selectAll()
            .where { (ScreenVersions.id eq versionId) and (ScreenVersions.screenId eq screenId) }
            .limit(1)
            .firstOrNull() != null
        if (!versionExists) return@newSuspendedTransaction false
        val now = Instant.now()
        ScreenDefinitions.update({ ScreenDefinitions.screenId eq screenId }) {
            it[currentVersionId] = versionId
            it[updatedAt] = now
        }
        true
    }

    /** Fetch the calling editor's draft for a screen, or null if none. */
    public suspend fun draftFor(screenId: String, editorId: UUID): ScreenDraftRow? = newSuspendedTransaction {
        ScreenDrafts
            .selectAll()
            .where { (ScreenDrafts.screenId eq screenId) and (ScreenDrafts.editorId eq editorId) }
            .limit(1)
            .firstOrNull()
            ?.toDraftRow()
    }

    /** Upsert a draft for the (screen, editor) pair. */
    public suspend fun upsertDraft(screenId: String, editorId: UUID, bodyJson: String): ScreenDraftRow =
        newSuspendedTransaction {
            val now = Instant.now()
            val existing = ScreenDrafts
                .selectAll()
                .where { (ScreenDrafts.screenId eq screenId) and (ScreenDrafts.editorId eq editorId) }
                .limit(1)
                .firstOrNull()
            if (existing != null) {
                ScreenDrafts.update({ ScreenDrafts.id eq existing[ScreenDrafts.id] }) {
                    it[ScreenDrafts.bodyJson] = bodyJson
                    it[ScreenDrafts.updatedAt] = now
                }
                ScreenDraftRow(
                    id = existing[ScreenDrafts.id],
                    screenId = screenId,
                    bodyJson = bodyJson,
                    editorId = editorId,
                    createdAt = existing[ScreenDrafts.createdAt],
                    updatedAt = now,
                )
            } else {
                val id = UUID.randomUUID()
                ScreenDrafts.insert {
                    it[ScreenDrafts.id] = id
                    it[ScreenDrafts.screenId] = screenId
                    it[ScreenDrafts.bodyJson] = bodyJson
                    it[ScreenDrafts.editorId] = editorId
                    it[ScreenDrafts.createdAt] = now
                    it[ScreenDrafts.updatedAt] = now
                }
                ensureDefinitionRow(screenId, now)
                ScreenDraftRow(id, screenId, bodyJson, editorId, now, now)
            }
        }

    /**
     * Promote the calling editor's draft to a new top published version. Returns the new
     * [ScreenVersionRow]. Throws [IllegalStateException] if no draft exists.
     */
    public suspend fun publishDraft(screenId: String, editorId: UUID): ScreenVersionRow = newSuspendedTransaction {
        val draft = ScreenDrafts
            .selectAll()
            .where { (ScreenDrafts.screenId eq screenId) and (ScreenDrafts.editorId eq editorId) }
            .limit(1)
            .firstOrNull() ?: error("no draft to publish for screen=$screenId editor=$editorId")
        val newRow = appendVersion(screenId, draft[ScreenDrafts.bodyJson], editorId)
        ScreenDrafts.deleteWhere {
            (ScreenDrafts.screenId eq screenId) and (ScreenDrafts.editorId eq editorId)
        }
        newRow
    }

    /**
     * Copy a historical version's body forward to a new top version. The history is never
     * mutated — revert works strictly by appending.
     */
    public suspend fun revertTo(screenId: String, sourceVersion: Int, editorId: UUID): ScreenVersionRow =
        newSuspendedTransaction {
            val source = ScreenVersions
                .selectAll()
                .where {
                    (ScreenVersions.screenId eq screenId) and
                        (ScreenVersions.versionNumber eq sourceVersion)
                }
                .limit(1)
                .firstOrNull() ?: error("source version $sourceVersion not found for $screenId")
            appendVersion(screenId, source[ScreenVersions.bodyJson], editorId)
        }

    /** Soft-delete a screen. History is preserved; the listing API hides it. */
    public suspend fun softDelete(screenId: String) {
        newSuspendedTransaction {
            val now = Instant.now()
            ScreenDefinitions.update({ ScreenDefinitions.screenId eq screenId }) {
                it[deletedAt] = now
                it[updatedAt] = now
            }
        }
    }

    private fun ensureDefinitionRow(screenId: String, now: Instant) {
        val existing = ScreenDefinitions
            .selectAll()
            .where { ScreenDefinitions.screenId eq screenId }
            .limit(1)
            .firstOrNull()
        if (existing == null) {
            ScreenDefinitions.insert {
                it[ScreenDefinitions.screenId] = screenId
                it[currentVersionId] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
        } else {
            ScreenDefinitions.update({ ScreenDefinitions.screenId eq screenId }) {
                it[updatedAt] = now
            }
        }
    }

    private fun appendVersion(screenId: String, bodyJson: String, editorId: UUID): ScreenVersionRow {
        val now = Instant.now()
        ensureDefinitionRow(screenId, now)
        val nextVersion = (
            ScreenVersions
                .select(ScreenVersions.versionNumber.max())
                .where { ScreenVersions.screenId eq screenId }
                .firstOrNull()
                ?.get(ScreenVersions.versionNumber.max())
                ?: 0
            ) + 1
        val id = UUID.randomUUID()
        // Two concurrent publishes to the same screen both read `nextVersion` and try to insert
        // it; the `screen_versions_screen_version_uq` unique index rejects the loser with an
        // ExposedSQLException. We deliberately let that propagate rather than insertIgnore — the
        // loser MUST NOT silently reuse the winner's version row. installStudioStatusPages maps
        // the constraint violation to a deterministic 409 so the caller can retry cleanly.
        ScreenVersions.insert {
            it[ScreenVersions.id] = id
            it[ScreenVersions.screenId] = screenId
            it[versionNumber] = nextVersion
            it[ScreenVersions.bodyJson] = bodyJson
            it[ScreenVersions.editorId] = editorId
            it[createdAt] = now
            it[publishedAt] = now
        }
        ScreenDefinitions.update({ ScreenDefinitions.screenId eq screenId }) {
            it[currentVersionId] = id
            it[updatedAt] = now
        }
        return ScreenVersionRow(id, screenId, nextVersion, bodyJson, editorId, now, now)
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toVersionRow(): ScreenVersionRow = ScreenVersionRow(
        id = this[ScreenVersions.id],
        screenId = this[ScreenVersions.screenId],
        versionNumber = this[ScreenVersions.versionNumber],
        bodyJson = this[ScreenVersions.bodyJson],
        editorId = this[ScreenVersions.editorId],
        createdAt = this[ScreenVersions.createdAt],
        publishedAt = this[ScreenVersions.publishedAt],
    )

    private fun org.jetbrains.exposed.sql.ResultRow.toDraftRow(): ScreenDraftRow = ScreenDraftRow(
        id = this[ScreenDrafts.id],
        screenId = this[ScreenDrafts.screenId],
        bodyJson = this[ScreenDrafts.bodyJson],
        editorId = this[ScreenDrafts.editorId],
        createdAt = this[ScreenDrafts.createdAt],
        updatedAt = this[ScreenDrafts.updatedAt],
    )
}
