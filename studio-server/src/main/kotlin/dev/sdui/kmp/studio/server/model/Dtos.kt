package dev.sdui.kmp.studio.server.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * API DTOs for the Studio admin REST surface. Defined here so route handlers stay focused on
 * HTTP plumbing and the wire shape is reviewable in one file.
 *
 * All timestamps are emitted as ISO-8601 strings. JSON bodies are passed through as
 * [JsonElement] so the schema linter sees them verbatim.
 */

/** `POST /admin/auth/login` request. */
@Serializable
public data class LoginRequest(
    public val email: String,
    public val password: String,
)

/** `POST /admin/auth/login` response on success. */
@Serializable
public data class LoginResponse(
    public val token: String,
    public val expiresAt: String,
    public val role: String,
)

/** A simple `{"error":"..."}` envelope. */
@Serializable
public data class ErrorResponse(public val error: String, public val details: List<String> = emptyList())

/** Item in the `GET /admin/screens` listing. */
@Serializable
public data class ScreenListItem(
    public val id: String,
    public val currentVersion: Int? = null,
    public val updatedAt: String,
    public val hasDraft: Boolean,
)

/** `GET /admin/screens/{id}` response. */
@Serializable
public data class ScreenDetailResponse(
    public val id: String,
    public val version: Int,
    public val body: JsonElement,
    public val publishedAt: String?,
    public val editorEmail: String,
)

/** `GET /admin/screens/{id}/draft` response. */
@Serializable
public data class DraftResponse(
    public val id: String,
    public val screenId: String,
    public val body: JsonElement,
    public val updatedAt: String,
)

/** `POST /admin/screens/{id}/publish` response. */
@Serializable
public data class PublishResponse(
    public val screenId: String,
    public val version: Int,
    public val publishedAt: String,
)

/** Item in the `GET /admin/screens/{id}/versions` listing. */
@Serializable
public data class VersionListItem(
    public val version: Int,
    public val createdAt: String,
    public val publishedAt: String?,
    public val editorId: String,
)

/** Item in the `GET /admin/audit` listing. */
@Serializable
public data class AuditListItem(
    public val id: String,
    public val screenId: String,
    public val editorId: String,
    public val action: String,
    public val fromVersion: Int? = null,
    public val toVersion: Int? = null,
    public val at: String,
    public val requestId: String,
    public val actorIp: String? = null,
    public val userAgent: String? = null,
)
