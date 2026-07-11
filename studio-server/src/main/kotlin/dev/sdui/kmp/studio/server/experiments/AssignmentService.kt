package dev.sdui.kmp.studio.server.experiments

import dev.sdui.kmp.studio.server.db.ScreenStore
import java.util.UUID

/**
 * Decides which `ScreenVersion.bodyJson` a given client should see for a screen, taking
 * audience targeting and weight-based bucketing into account.
 *
 * Decision priority (top wins):
 *  1. No active experiment for the screen → serve the screen's currently-published version.
 *  2. Client already has a sticky assignment for an active experiment → reuse it (sticky
 *     semantics, see ADR-0018 §"Sticky assignments").
 *  3. Client matches every linked audience AND falls into a variant bucket → persist and
 *     return that variant.
 *  4. Client does not match the audiences → serve the published version.
 *
 * Hash function: `(experimentId + clientId).hashCode().mod(100)`. Documented in
 * ADR-0018 §"Hash function choice". Deterministic, no third-party dependency, stable
 * across JVMs (see Java's `String.hashCode` contract, frozen since Java 1.0).
 */
public class AssignmentService(
    private val store: ExperimentStore = ExperimentStore,
) {
    /**
     * Resolve the bodyJson string the calling client should render for [screenId].
     *
     * Returns null when the screen has no published version AND no active experiment with at
     * least one variant — same nullability the existing route returns to clients today.
     */
    @Suppress("ReturnCount")
    public suspend fun assign(
        screenId: String,
        clientId: String,
        context: Map<String, String>,
    ): AssignmentResult {
        // Active experiments only. We pick the first one — the model assumes one active
        // experiment per screen at a time. If editors stand up multiple in parallel, the most
        // recently created one wins (ordering is in `listExperiments`); a hard-fail is too
        // strict for a self-service tool.
        val active = store
            .listExperiments(screenId = screenId, status = ExperimentStatus.Active)
            .firstOrNull()
            ?: return fallbackToPublished(screenId, reason = AssignmentReason.NoActiveExperiment)

        val variants = store.listVariants(active.id)
        if (variants.isEmpty()) {
            return fallbackToPublished(screenId, reason = AssignmentReason.NoActiveExperiment)
        }

        // Sticky path — reuse the prior decision regardless of weight or audience changes.
        store.assignmentFor(active.id, clientId)?.let { existingVariantId ->
            val variant = variants.firstOrNull { it.id == existingVariantId }
                ?: return fallbackToPublished(screenId, reason = AssignmentReason.StickyVariantMissing)
            return resolveVariant(active.id, variant, AssignmentReason.Sticky)
        }

        // Audience filter. Empty audience list means "everyone is eligible".
        val audiences = store.audiencesFor(active.id)
        val eligible = audiences.isEmpty() || audiences.all { it.predicate.evaluate(context) }
        if (!eligible) {
            return fallbackToPublished(screenId, reason = AssignmentReason.AudienceExcluded)
        }

        val chosen = pickByWeight(active.id, clientId, variants)
            ?: return fallbackToPublished(screenId, reason = AssignmentReason.WeightSumZero)

        store.persistAssignment(active.id, clientId, chosen.id)
        return resolveVariant(active.id, chosen, AssignmentReason.NewlyAssigned)
    }

    private suspend fun fallbackToPublished(screenId: String, reason: AssignmentReason): AssignmentResult {
        val current = ScreenStore.currentVersion(screenId)
            ?: return AssignmentResult(
                screenVersionId = null,
                bodyJson = null,
                experimentId = null,
                variantId = null,
                reason = reason,
            )
        return AssignmentResult(
            screenVersionId = current.id,
            bodyJson = current.bodyJson,
            experimentId = null,
            variantId = null,
            reason = reason,
        )
    }

    private suspend fun resolveVariant(
        experimentId: String,
        variant: VariantRow,
        reason: AssignmentReason,
    ): AssignmentResult {
        val versionRow = ScreenStore.versionById(variant.screenVersionId)
        return AssignmentResult(
            screenVersionId = variant.screenVersionId,
            bodyJson = versionRow?.bodyJson,
            experimentId = experimentId,
            variantId = variant.id,
            reason = reason,
        )
    }

    /**
     * Hash `(experimentId || clientId)` to a 0..99 bucket and pick the variant whose
     * cumulative weight covers it. Returns null if total weight is zero (defence-in-depth —
     * the route layer rejects this at create time, but a corrupted DB row should not crash).
     *
     * Internal so it's exercisable from determinism tests.
     */
    @Suppress("MagicNumber")
    internal fun pickByWeight(
        experimentId: String,
        clientId: String,
        variants: List<VariantRow>,
    ): VariantRow? {
        val totalWeight = variants.sumOf { it.weight }
        if (totalWeight <= 0) return null
        val bucket = bucketFor(experimentId, clientId)
        // Order matters for determinism: ExperimentStore returns variants sorted by
        // weight DESC + name ASC, which is stable across DB engines.
        var cumulative = 0
        for (v in variants) {
            cumulative += v.weight
            // Convert the raw bucket (0..99) to bucket-of-totalWeight space so 0..100-weight
            // experiments and unevenly-summed test fixtures both bucket consistently.
            val scaled = (bucket * totalWeight) / BUCKET_SIZE
            if (scaled < cumulative) return v
        }
        return variants.last()
    }

    public companion object {
        /**
         * Bucket size. 100 is the canonical "percentage" granularity. Variants whose weights
         * sum to 100 land each client in exactly the same bucket as the percentage they expect.
         */
        public const val BUCKET_SIZE: Int = 100

        /**
         * Deterministic hash for `(experimentId, clientId)`. The implementation uses
         * Kotlin/JVM's `String.hashCode` (delegating to `java.lang.String.hashCode`) which is
         * specified in the JLS as `s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]`. That makes
         * the value byte-identical across JVM vendors, releases, and platforms — fine for our
         * "consistent assignment" requirement. Sweeping kotlinx-* did not surface a portable
         * Murmur3 implementation in this version of the toolchain; if one ships later, this
         * helper is the single replacement point and ADR-0018 documents the swap criteria.
         */
        public fun bucketFor(experimentId: String, clientId: String): Int {
            val key = "$experimentId|$clientId"
            // hashCode is signed; flip the sign bit before mod so negative inputs don't bias.
            val unsigned = key.hashCode().toLong() and 0xFFFF_FFFFL
            return (unsigned % BUCKET_SIZE).toInt()
        }
    }
}

/**
 * Why the assignment came back the way it did. Surfaced in the JSON response so studio
 * operators inspecting traffic can tell a "no experiment running" from a "client did not
 * match audience" without reading server logs.
 */
public enum class AssignmentReason(public val wire: String) {
    NoActiveExperiment("no_active_experiment"),
    Sticky("sticky"),
    NewlyAssigned("newly_assigned"),
    AudienceExcluded("audience_excluded"),
    WeightSumZero("weight_sum_zero"),
    StickyVariantMissing("sticky_variant_missing"),
}

/**
 * Result of [AssignmentService.assign]. Exactly one of [bodyJson] / [screenVersionId] is null
 * if the screen has no published version AND no eligible variant. Routes serialize this
 * minimally — `bodyJson` is forwarded verbatim as the response body for `/screens/{id}/assign`.
 */
public data class AssignmentResult(
    public val screenVersionId: UUID?,
    public val bodyJson: String?,
    public val experimentId: String?,
    public val variantId: String?,
    public val reason: AssignmentReason,
)
