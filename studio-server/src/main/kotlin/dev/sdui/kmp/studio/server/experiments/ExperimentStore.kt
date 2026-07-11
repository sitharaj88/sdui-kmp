package dev.sdui.kmp.studio.server.experiments

import dev.sdui.kmp.studio.server.db.Audiences
import dev.sdui.kmp.studio.server.db.ExperimentAssignments
import dev.sdui.kmp.studio.server.db.ExperimentAudiences
import dev.sdui.kmp.studio.server.db.ExperimentVariants
import dev.sdui.kmp.studio.server.db.Experiments
import dev.sdui.kmp.studio.server.db.ScreenDefinitions
import dev.sdui.kmp.studio.server.db.ScreenVersions
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Lifecycle status of an [ExperimentRow]. Mirrors the wire-string column on
 * [Experiments.status]. Only [Active] experiments are eligible for client assignment.
 */
public enum class ExperimentStatus(public val wire: String) {
    Draft("draft"),
    Active("active"),
    Paused("paused"),
    Completed("completed");

    public companion object {
        /** Parse a wire string. Defaults to [Draft] for unknowns (defence-in-depth). */
        public fun parse(value: String): ExperimentStatus = values().firstOrNull { it.wire == value } ?: Draft
    }
}

/** Read projection of an [Experiments] row. */
public data class ExperimentRow(
    public val id: String,
    public val screenId: String,
    public val name: String,
    public val description: String?,
    public val status: ExperimentStatus,
    public val createdAt: Instant,
    public val updatedAt: Instant,
    public val createdBy: UUID,
)

/** Read projection of an [ExperimentVariants] row. */
public data class VariantRow(
    public val id: String,
    public val experimentId: String,
    public val name: String,
    public val weight: Int,
    public val screenVersionId: UUID,
    public val createdAt: Instant,
    public val createdBy: UUID,
)

/** Read projection of an [Audiences] row, with the predicate already deserialized. */
public data class AudienceRow(
    public val id: String,
    public val name: String,
    public val description: String?,
    public val predicate: AudiencePredicate,
    public val createdAt: Instant,
    public val createdBy: UUID,
)

/** Lightweight result row from `GET /experiments/{id}/results`. */
public data class VariantAssignmentCount(public val variantId: String, public val count: Long)

/**
 * Combined CRUD surface for experiments, variants, audiences, and the join. Every method runs
 * in a `newSuspendedTransaction` so route handlers can express each operation as a single
 * suspend call — same pattern as [dev.sdui.kmp.studio.server.db.ScreenStore].
 */
@Suppress("TooManyFunctions")
public object ExperimentStore {
    /** Shared [Json] used to encode/decode predicates into the audiences row. */
    public val PredicateJson: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    // ---- experiments ----

    /** Insert a new experiment row in `draft` status. */
    public suspend fun createExperiment(
        id: String,
        screenId: String,
        name: String,
        description: String?,
        createdBy: UUID,
    ): ExperimentRow = newSuspendedTransaction {
        val now = Instant.now()
        val screenExists = ScreenDefinitions
            .selectAll()
            .where { ScreenDefinitions.screenId eq screenId }
            .limit(1)
            .firstOrNull() != null
        require(screenExists) { "screen $screenId does not exist" }
        Experiments.insert {
            it[Experiments.id] = id
            it[Experiments.screenId] = screenId
            it[Experiments.name] = name
            it[Experiments.description] = description
            it[Experiments.status] = ExperimentStatus.Draft.wire
            it[Experiments.createdAt] = now
            it[Experiments.updatedAt] = now
            it[Experiments.createdBy] = createdBy
        }
        ExperimentRow(id, screenId, name, description, ExperimentStatus.Draft, now, now, createdBy)
    }

    /** List all experiments, optionally filtered by screen and/or status. */
    public suspend fun listExperiments(
        screenId: String? = null,
        status: ExperimentStatus? = null,
    ): List<ExperimentRow> = newSuspendedTransaction {
        Experiments
            .selectAll()
            .let { q ->
                if (screenId != null) q.where { Experiments.screenId eq screenId } else q
            }
            .let { q ->
                if (status != null) q.andWhere { Experiments.status eq status.wire } else q
            }
            .orderBy(Experiments.createdAt, SortOrder.DESC)
            .map { it.toExperimentRow() }
    }

    /** Fetch a single experiment by id. */
    public suspend fun getExperiment(id: String): ExperimentRow? = newSuspendedTransaction {
        Experiments
            .selectAll()
            .where { Experiments.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toExperimentRow()
    }

    /** Patch an experiment's status. Returns true on a hit, false if no row matched [id]. */
    public suspend fun updateStatus(id: String, status: ExperimentStatus): Boolean = newSuspendedTransaction {
        val updated = Experiments.update({ Experiments.id eq id }) {
            it[Experiments.status] = status.wire
            it[updatedAt] = Instant.now()
        }
        updated > 0
    }

    // ---- variants ----

    /** Insert a variant. Throws if [screenVersionId] is not a known [ScreenVersions] row. */
    public suspend fun addVariant(
        id: String,
        experimentId: String,
        name: String,
        weight: Int,
        screenVersionId: UUID,
        createdBy: UUID,
    ): VariantRow = newSuspendedTransaction {
        val now = Instant.now()
        val versionExists = ScreenVersions
            .selectAll()
            .where { ScreenVersions.id eq screenVersionId }
            .limit(1)
            .firstOrNull() != null
        require(versionExists) { "screen version $screenVersionId does not exist" }
        ExperimentVariants.insert {
            it[ExperimentVariants.id] = id
            it[ExperimentVariants.experimentId] = experimentId
            it[ExperimentVariants.name] = name
            it[ExperimentVariants.weight] = weight
            it[ExperimentVariants.screenVersionId] = screenVersionId
            it[ExperimentVariants.createdAt] = now
            it[ExperimentVariants.createdBy] = createdBy
        }
        VariantRow(id, experimentId, name, weight, screenVersionId, now, createdBy)
    }

    /** List variants for an experiment, ordered by weight DESC then name. */
    public suspend fun listVariants(experimentId: String): List<VariantRow> = newSuspendedTransaction {
        ExperimentVariants
            .selectAll()
            .where { ExperimentVariants.experimentId eq experimentId }
            .orderBy(ExperimentVariants.weight to SortOrder.DESC, ExperimentVariants.name to SortOrder.ASC)
            .map { it.toVariantRow() }
    }

    /** Fetch a variant by id. */
    public suspend fun getVariant(variantId: String): VariantRow? = newSuspendedTransaction {
        ExperimentVariants
            .selectAll()
            .where { ExperimentVariants.id eq variantId }
            .limit(1)
            .firstOrNull()
            ?.toVariantRow()
    }

    // ---- audiences ----

    /** Insert an audience. Caller must validate the predicate compiles. */
    public suspend fun createAudience(
        id: String,
        name: String,
        description: String?,
        predicate: AudiencePredicate,
        createdBy: UUID,
    ): AudienceRow = newSuspendedTransaction {
        val now = Instant.now()
        val encoded = PredicateJson.encodeToString(AudiencePredicate.serializer(), predicate)
        Audiences.insert {
            it[Audiences.id] = id
            it[Audiences.name] = name
            it[Audiences.description] = description
            it[Audiences.predicateJson] = encoded
            it[Audiences.createdAt] = now
            it[Audiences.createdBy] = createdBy
        }
        AudienceRow(id, name, description, predicate, now, createdBy)
    }

    /** List every audience. */
    public suspend fun listAudiences(): List<AudienceRow> = newSuspendedTransaction {
        Audiences
            .selectAll()
            .orderBy(Audiences.name, SortOrder.ASC)
            .mapNotNull { it.toAudienceRowOrNull() }
    }

    /** Fetch a single audience by id. */
    public suspend fun getAudience(id: String): AudienceRow? = newSuspendedTransaction {
        Audiences
            .selectAll()
            .where { Audiences.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toAudienceRowOrNull()
    }

    // ---- experiment-audience join ----

    /** Link an audience to an experiment. Idempotent — duplicates are absorbed. */
    public suspend fun linkAudience(experimentId: String, audienceId: String) {
        newSuspendedTransaction {
            val exists = ExperimentAudiences
                .selectAll()
                .where {
                    (ExperimentAudiences.experimentId eq experimentId) and
                        (ExperimentAudiences.audienceId eq audienceId)
                }
                .limit(1)
                .firstOrNull() != null
            if (!exists) {
                ExperimentAudiences.insert {
                    it[ExperimentAudiences.experimentId] = experimentId
                    it[ExperimentAudiences.audienceId] = audienceId
                }
            }
        }
    }

    /** Audiences linked to the experiment. AND across them at evaluation time. */
    public suspend fun audiencesFor(experimentId: String): List<AudienceRow> = newSuspendedTransaction {
        val ids = ExperimentAudiences
            .selectAll()
            .where { ExperimentAudiences.experimentId eq experimentId }
            .map { it[ExperimentAudiences.audienceId] }
        if (ids.isEmpty()) {
            emptyList()
        } else {
            Audiences
                .selectAll()
                .where { Audiences.id inList ids }
                .mapNotNull { it.toAudienceRowOrNull() }
        }
    }

    // ---- assignments ----

    /** Look up an existing sticky assignment. */
    public suspend fun assignmentFor(experimentId: String, clientId: String): String? =
        newSuspendedTransaction {
            ExperimentAssignments
                .selectAll()
                .where {
                    (ExperimentAssignments.experimentId eq experimentId) and
                        (ExperimentAssignments.clientId eq clientId)
                }
                .limit(1)
                .firstOrNull()
                ?.get(ExperimentAssignments.variantId)
        }

    /**
     * Persist a fresh assignment. Idempotent: if a row already exists we keep the original
     * variant (sticky semantics — see ADR-0018) and return that one instead.
     *
     * The insert uses `insertIgnore` and re-reads the stored row so two concurrent first-time
     * assign() calls for the same `(experimentId, clientId)` converge silently instead of the
     * loser throwing a PK-violation [org.jetbrains.exposed.exceptions.ExposedSQLException]. This
     * mirrors the RBAC stores (see [dev.sdui.kmp.studio.server.rbac.PermissionStore]); it is
     * safe because the caller's weight-bucket pick is deterministic per key, so both racers
     * choose the same variant and the surviving row is authoritative.
     */
    public suspend fun persistAssignment(
        experimentId: String,
        clientId: String,
        variantId: String,
    ): String = newSuspendedTransaction {
        val existing = ExperimentAssignments
            .selectAll()
            .where {
                (ExperimentAssignments.experimentId eq experimentId) and
                    (ExperimentAssignments.clientId eq clientId)
            }
            .limit(1)
            .firstOrNull()
        if (existing != null) {
            existing[ExperimentAssignments.variantId]
        } else {
            val insert = ExperimentAssignments.insertIgnore {
                it[ExperimentAssignments.experimentId] = experimentId
                it[ExperimentAssignments.clientId] = clientId
                it[ExperimentAssignments.variantId] = variantId
                it[ExperimentAssignments.assignedAt] = Instant.now()
            }
            if (insert.insertedCount > 0) {
                // Common path: our insert won, no extra round-trip.
                variantId
            } else {
                // A concurrent first assign beat us to the PK; the stored row is the sticky truth.
                // Re-read it (falling back to our own deterministic pick if the read is empty).
                ExperimentAssignments
                    .selectAll()
                    .where {
                        (ExperimentAssignments.experimentId eq experimentId) and
                            (ExperimentAssignments.clientId eq clientId)
                    }
                    .limit(1)
                    .firstOrNull()
                    ?.get(ExperimentAssignments.variantId)
                    ?: variantId
            }
        }
    }

    /** Counts of assignments per variant for the given experiment. */
    public suspend fun assignmentCounts(experimentId: String): List<VariantAssignmentCount> =
        newSuspendedTransaction {
            ExperimentAssignments
                .selectAll()
                .where { ExperimentAssignments.experimentId eq experimentId }
                .groupBy { it[ExperimentAssignments.variantId] }
                .map { (vid, rows) -> VariantAssignmentCount(vid, rows.size.toLong()) }
                .sortedByDescending { it.count }
        }

    /** Test-only seam: wipe every targeting row. NEVER call from production code. */
    internal suspend fun clearAllForTesting() {
        newSuspendedTransaction {
            ExperimentAssignments.deleteAll()
            ExperimentAudiences.deleteAll()
            ExperimentVariants.deleteAll()
            Experiments.deleteAll()
            Audiences.deleteAll()
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toExperimentRow(): ExperimentRow = ExperimentRow(
        id = this[Experiments.id],
        screenId = this[Experiments.screenId],
        name = this[Experiments.name],
        description = this[Experiments.description],
        status = ExperimentStatus.parse(this[Experiments.status]),
        createdAt = this[Experiments.createdAt],
        updatedAt = this[Experiments.updatedAt],
        createdBy = this[Experiments.createdBy],
    )

    private fun org.jetbrains.exposed.sql.ResultRow.toVariantRow(): VariantRow = VariantRow(
        id = this[ExperimentVariants.id],
        experimentId = this[ExperimentVariants.experimentId],
        name = this[ExperimentVariants.name],
        weight = this[ExperimentVariants.weight],
        screenVersionId = this[ExperimentVariants.screenVersionId],
        createdAt = this[ExperimentVariants.createdAt],
        createdBy = this[ExperimentVariants.createdBy],
    )

    private fun org.jetbrains.exposed.sql.ResultRow.toAudienceRowOrNull(): AudienceRow? {
        val raw = this[Audiences.predicateJson]
        val pred = runCatching {
            PredicateJson.decodeFromString(AudiencePredicate.serializer(), raw)
        }.getOrNull() ?: return null
        return AudienceRow(
            id = this[Audiences.id],
            name = this[Audiences.name],
            description = this[Audiences.description],
            predicate = pred,
            createdAt = this[Audiences.createdAt],
            createdBy = this[Audiences.createdBy],
        )
    }
}
