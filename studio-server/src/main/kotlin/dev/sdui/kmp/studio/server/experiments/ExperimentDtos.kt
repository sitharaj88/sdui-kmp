package dev.sdui.kmp.studio.server.experiments

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the experiments admin REST surface. Defined here so route handlers stay focused
 * on plumbing and the JSON shape is reviewable in one file.
 *
 * Every field is `Serializable` and uses `@Serializable` defaults so additive evolution works
 * the same way as the protocol's red lines: only ever add new fields, never remove or retype.
 */

/** `POST /experiments` request. */
@Serializable
public data class CreateExperimentRequest(
    public val id: String,
    public val screenId: String,
    public val name: String,
    public val description: String? = null,
)

/** `POST /experiments/{id}/variants` request. */
@Serializable
public data class CreateVariantRequest(
    public val id: String,
    public val name: String,
    public val weight: Int,
    public val screenVersionId: String,
)

/** `POST /audiences` request. */
@Serializable
public data class CreateAudienceRequest(
    public val id: String,
    public val name: String,
    public val description: String? = null,
    public val predicate: AudiencePredicate,
)

/** `PATCH /experiments/{id}/status` request. */
@Serializable
public data class UpdateStatusRequest(public val status: String)

/** Listing item — `GET /experiments` and `GET /experiments/{id}`. */
@Serializable
public data class ExperimentDto(
    public val id: String,
    public val screenId: String,
    public val name: String,
    public val description: String? = null,
    public val status: String,
    public val createdAt: String,
    public val updatedAt: String,
    public val createdBy: String,
)

/** Variant view. */
@Serializable
public data class VariantDto(
    public val id: String,
    public val experimentId: String,
    public val name: String,
    public val weight: Int,
    public val screenVersionId: String,
    public val createdAt: String,
)

/** Audience view, with the predicate echoed back as a tree so the editor UI can render it. */
@Serializable
public data class AudienceDto(
    public val id: String,
    public val name: String,
    public val description: String? = null,
    public val predicate: AudiencePredicate,
    public val createdAt: String,
)

/** `GET /experiments/{id}/results` row. */
@Serializable
public data class VariantCountDto(public val variantId: String, public val count: Long)

/** `GET /screens/{id}/assign` response — used by sample-server's `StudioAssignmentClient`. */
@Serializable
public data class AssignResponse(
    public val screenId: String,
    public val experimentId: String? = null,
    public val variantId: String? = null,
    public val screenVersionId: String? = null,
    public val reason: String,
    public val body: kotlinx.serialization.json.JsonElement? = null,
)
