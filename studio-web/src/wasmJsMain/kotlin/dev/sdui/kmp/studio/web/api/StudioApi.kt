package dev.sdui.kmp.studio.web.api

import dev.sdui.kmp.studio.web.state.AuthState
import dev.sdui.kmp.transport.http.installBearerAuth
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Thin Ktor-client wrapper over the Studio admin REST API exposed by `:studio-server`.
 *
 * Single-purpose: the Studio admin session is held entirely inside this client (via the
 * `installBearerAuth` token provider) and never shared with any other HttpClient in the page.
 * The end-user preview pane uses [`SduiHost`](../../../../../../../../../runtime) wired to a
 * `staticScreenSource`, never to a live HTTP fetch — so admin credentials physically cannot
 * leak into a preview request.
 *
 * Endpoints covered:
 *  * `POST /admin/auth/login` — exchange email + password for a JWT.
 *  * `POST /admin/auth/logout` — revoke the session row server-side.
 *  * `GET  /admin/screens` — list screen summaries (with `hasDraft` flag).
 *  * `GET  /admin/screens/{id}` — fetch the currently-published screen body.
 *  * `GET  /admin/screens/{id}/draft` — fetch the calling editor's in-progress draft, if any.
 *  * `PUT  /admin/screens/{id}/draft` — upsert the draft, server-validated against
 *    `Screen.serializer()`.
 *  * `POST /admin/screens/{id}/publish` — promote the draft to a published version.
 *  * `POST /admin/screens/{id}/revert?to=N` — admin-only revert to a prior version.
 *  * `DELETE /admin/screens/{id}` — admin-only soft-delete.
 *  * `GET  /admin/screens/{id}/versions` — paginated history (newest first).
 *  * `GET  /admin/audit` — filtered audit log.
 *
 * Construction note: the [engine] parameter is overridable so tests can plug in `MockEngine`.
 * Production callers omit it and get the platform default (Ktor `Js` engine in the browser).
 */
public class StudioApi(
    private val baseUrl: String,
    private val authState: AuthState,
    engine: HttpClientEngine? = null,
    additionalConfig: HttpClientConfig<*>.() -> Unit = {},
) {
    private val json: Json = Json {
        // Match :transport-http's posture: tolerant of additive server fields, strict for
        // everything else. Studio responses are bespoke admin DTOs so they don't need the
        // SduiJson polymorphic discriminators.
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    /** Pretty-printer used to emit the editor's initial JSON. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val prettyJson: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val client: HttpClient = run {
        val config: HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) {
                json(json)
            }
            // Threads the AuthState's current token through every request. Returns null
            // before login → Ktor omits the Authorization header → /admin/auth/login can
            // still be called unauthenticated, which is what we want.
            installBearerAuth { authState.token }
            additionalConfig()
        }
        if (engine == null) HttpClient(Js, config) else HttpClient(engine, config)
    }

    /**
     * Exchange an email + password for an admin JWT.
     *
     * On success, the returned token is *not* automatically pushed into [AuthState] —
     * the caller (the login screen) decides when the UI should transition. This keeps the
     * API wrapper a pure data layer and the composables fully in charge of state changes.
     */
    public suspend fun login(email: String, password: String): LoginResult {
        val response = client.post("${baseUrl.trimEnd('/')}/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = password))
        }
        return when {
            response.status.isSuccess() -> {
                val body = json.decodeFromString(LoginResponse.serializer(), response.bodyAsText())
                LoginResult.Success(token = body.token, role = body.role, email = email)
            }
            response.status == HttpStatusCode.Unauthorized -> LoginResult.InvalidCredentials
            else -> LoginResult.Failure(statusCode = response.status.value)
        }
    }

    /**
     * Revoke the current session server-side. The caller is responsible for clearing
     * [AuthState] and routing back to the login screen.
     */
    public suspend fun logout(): Boolean {
        val response = client.post("${baseUrl.trimEnd('/')}/admin/auth/logout")
        return response.status.isSuccess()
    }

    /** List screens visible to the operator. */
    public suspend fun listScreens(): List<ScreenSummary> {
        val response = client.get("${baseUrl.trimEnd('/')}/admin/screens")
        check(response.status.isSuccess()) { "GET /admin/screens failed: ${response.status}" }
        return json.decodeFromString(ListSerializer(ScreenSummary.serializer()), response.bodyAsText())
    }

    /** Fetch the currently-published screen JSON. Returns the body pretty-printed. */
    public suspend fun getScreen(id: String): ScreenDetail {
        val response = client.get("${baseUrl.trimEnd('/')}/admin/screens/$id")
        check(response.status.isSuccess()) { "GET /admin/screens/$id failed: ${response.status}" }
        val parsed = json.decodeFromString(ScreenDetailResponse.serializer(), response.bodyAsText())
        return ScreenDetail(
            id = parsed.id,
            version = parsed.version,
            body = prettyJson.encodeToString(JsonElement.serializer(), parsed.body),
            publishedAt = parsed.publishedAt,
            editorEmail = parsed.editorEmail,
        )
    }

    /**
     * Fetch the calling editor's draft for [id]. Returns `null` if no draft exists — the
     * server signals that with HTTP 404, which is a normal flow (operator hasn't started
     * editing yet) rather than an error.
     */
    public suspend fun getDraft(id: String): DraftDetail? {
        val response = client.get("${baseUrl.trimEnd('/')}/admin/screens/$id/draft")
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "GET /admin/screens/$id/draft failed: ${response.status}" }
        val parsed = json.decodeFromString(DraftResponse.serializer(), response.bodyAsText())
        return DraftDetail(
            id = parsed.id,
            screenId = parsed.screenId,
            body = prettyJson.encodeToString(JsonElement.serializer(), parsed.body),
            updatedAt = parsed.updatedAt,
        )
    }

    /**
     * Upsert the editor's draft. The server validates the body against `Screen.serializer()`;
     * on validation failure we return [DraftSaveResult.Invalid] with the server-reported
     * violations so the editor can fix the JSON without a network refresh.
     */
    public suspend fun putDraft(id: String, body: String): DraftSaveResult {
        // Round-trip via JsonElement so we forward exactly what the editor typed.
        val element = try {
            json.parseToJsonElement(body)
        } catch (t: Throwable) {
            return DraftSaveResult.Invalid(listOf("client-side parse: ${t.message ?: "malformed JSON"}"))
        }
        val response = client.put("${baseUrl.trimEnd('/')}/admin/screens/$id/draft") {
            contentType(ContentType.Application.Json)
            setBody(element)
        }
        return when {
            response.status.isSuccess() -> {
                val parsed = json.decodeFromString(DraftResponse.serializer(), response.bodyAsText())
                DraftSaveResult.Saved(
                    DraftDetail(
                        id = parsed.id,
                        screenId = parsed.screenId,
                        body = prettyJson.encodeToString(JsonElement.serializer(), parsed.body),
                        updatedAt = parsed.updatedAt,
                    ),
                )
            }
            response.status == HttpStatusCode.BadRequest -> {
                val err = runCatching {
                    json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText())
                }.getOrNull()
                DraftSaveResult.Invalid(err?.details.orEmpty().ifEmpty { listOf(err?.error.orEmpty()) })
            }
            else -> DraftSaveResult.Failure(statusCode = response.status.value)
        }
    }

    /** Promote the draft for [id] to a published version. Returns the new version number. */
    public suspend fun publish(id: String): PublishResult {
        val response = client.post("${baseUrl.trimEnd('/')}/admin/screens/$id/publish")
        return when {
            response.status.isSuccess() -> {
                val parsed = json.decodeFromString(PublishResponse.serializer(), response.bodyAsText())
                PublishResult.Ok(version = parsed.version, publishedAt = parsed.publishedAt)
            }
            response.status == HttpStatusCode.NotFound -> PublishResult.NoDraft
            else -> PublishResult.Failure(statusCode = response.status.value)
        }
    }

    /** Admin-only: revert [id] to version [toVersion]; the server materialises a new version. */
    public suspend fun revert(id: String, toVersion: Int): PublishResult {
        val response = client.post("${baseUrl.trimEnd('/')}/admin/screens/$id/revert") {
            parameter("to", toVersion)
        }
        return when {
            response.status.isSuccess() -> {
                val parsed = json.decodeFromString(PublishResponse.serializer(), response.bodyAsText())
                PublishResult.Ok(version = parsed.version, publishedAt = parsed.publishedAt)
            }
            response.status == HttpStatusCode.Forbidden -> PublishResult.Forbidden
            response.status == HttpStatusCode.NotFound -> PublishResult.NoDraft
            else -> PublishResult.Failure(statusCode = response.status.value)
        }
    }

    /** Admin-only soft-delete. */
    public suspend fun deleteScreen(id: String): Boolean {
        val response = client.delete("${baseUrl.trimEnd('/')}/admin/screens/$id")
        return response.status.isSuccess()
    }

    /** Paginated version history, newest first. */
    public suspend fun listVersions(
        id: String,
        limit: Int = DEFAULT_VERSION_LIMIT,
        offset: Long = 0,
    ): List<ScreenVersion> {
        val response = client.get("${baseUrl.trimEnd('/')}/admin/screens/$id/versions") {
            parameter("limit", limit)
            parameter("offset", offset)
        }
        check(response.status.isSuccess()) { "GET /admin/screens/$id/versions failed: ${response.status}" }
        return json.decodeFromString(ListSerializer(ScreenVersion.serializer()), response.bodyAsText())
    }

    /**
     * Filtered audit-log fetch. Any of [screenId], [editorId], [from], [to] may be `null` to
     * leave that filter off. [from] / [to] are ISO-8601 strings (the server validates them via
     * `Instant.parse`).
     */
    public suspend fun listAudit(
        screenId: String? = null,
        editorId: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = AUDIT_DEFAULT_LIMIT,
        offset: Long = 0,
    ): List<AuditEntry> {
        val response = client.get("${baseUrl.trimEnd('/')}/admin/audit") {
            parameter("limit", limit)
            parameter("offset", offset)
            screenId?.takeIf { it.isNotBlank() }?.let { parameter("screenId", it) }
            editorId?.takeIf { it.isNotBlank() }?.let { parameter("editorId", it) }
            from?.takeIf { it.isNotBlank() }?.let { parameter("from", it) }
            to?.takeIf { it.isNotBlank() }?.let { parameter("to", it) }
        }
        check(response.status.isSuccess()) { "GET /admin/audit failed: ${response.status}" }
        return json.decodeFromString(ListSerializer(AuditEntry.serializer()), response.bodyAsText())
    }

    /** Releases the underlying Ktor client. Call from a `DisposableEffect.onDispose` block. */
    public fun close() {
        client.close()
    }

    // ---- M-S6: experiments + audiences --------------------------------------------------------

    /** List experiments, optionally filtered by `screenId` and `status`. */
    public suspend fun listExperiments(screenId: String? = null, status: String? = null): List<ExperimentSummary> {
        val response = client.get("${baseUrl.trimEnd('/')}/experiments") {
            screenId?.takeIf { it.isNotBlank() }?.let { parameter("screenId", it) }
            status?.takeIf { it.isNotBlank() }?.let { parameter("status", it) }
        }
        check(response.status.isSuccess()) { "GET /experiments failed: ${response.status}" }
        return json.decodeFromString(ListSerializer(ExperimentSummary.serializer()), response.bodyAsText())
    }

    /** List variants for a single experiment. */
    public suspend fun listVariants(experimentId: String): List<VariantSummary> {
        val response = client.get("${baseUrl.trimEnd('/')}/experiments/$experimentId/variants")
        check(response.status.isSuccess()) { "GET variants failed: ${response.status}" }
        return json.decodeFromString(ListSerializer(VariantSummary.serializer()), response.bodyAsText())
    }

    /** List every audience defined in the studio. */
    public suspend fun listAudiences(): List<AudienceSummary> {
        val response = client.get("${baseUrl.trimEnd('/')}/audiences")
        check(response.status.isSuccess()) { "GET /audiences failed: ${response.status}" }
        return json.decodeFromString(ListSerializer(AudienceSummary.serializer()), response.bodyAsText())
    }

    /** Update experiment status (`draft` / `active` / `paused` / `completed`). */
    public suspend fun setExperimentStatus(experimentId: String, status: String): Boolean {
        val response = client.patch("${baseUrl.trimEnd('/')}/experiments/$experimentId/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"$status"}""")
        }
        return response.status.isSuccess()
    }

    /** Promote a variant to the screen's published version. Admin-only. */
    public suspend fun promoteVariant(experimentId: String, variantId: String): Boolean {
        val response = client.post(
            "${baseUrl.trimEnd('/')}/experiments/$experimentId/variants/$variantId/promote",
        )
        return response.status.isSuccess()
    }

    /** Link an audience to an experiment. */
    public suspend fun linkAudience(experimentId: String, audienceId: String): Boolean {
        val response = client.post(
            "${baseUrl.trimEnd('/')}/experiments/$experimentId/audiences/$audienceId",
        )
        return response.status.isSuccess()
    }

    /** Variant-count results from `/experiments/{id}/results`. */
    public suspend fun experimentResults(experimentId: String): List<VariantCount> {
        val response = client.get("${baseUrl.trimEnd('/')}/experiments/$experimentId/results")
        check(response.status.isSuccess()) { "GET results failed: ${response.status}" }
        return json.decodeFromString(ListSerializer(VariantCount.serializer()), response.bodyAsText())
    }

    private companion object {
        private const val AUDIT_DEFAULT_LIMIT: Int = 100
        private const val DEFAULT_VERSION_LIMIT: Int = 50
    }
}

@Serializable
private data class LoginRequest(val email: String, val password: String)

@Serializable
private data class LoginResponse(val token: String, val expiresAt: String? = null, val role: String? = null)

@Serializable
internal data class ErrorResponse(val error: String = "", val details: List<String> = emptyList())

/** Outcome of a [StudioApi.login] call — explicit so the UI can branch without throwing. */
public sealed interface LoginResult {
    /** Operator authenticated successfully. */
    public data class Success(val token: String, val role: String?, val email: String?) : LoginResult

    /** 401 from the server — wrong email or password. */
    public data object InvalidCredentials : LoginResult

    /** Any other non-2xx status the server returned. Surface a generic error to the user. */
    public data class Failure(val statusCode: Int) : LoginResult
}

/**
 * Compact screen-summary row rendered in the screens list. Mirrors the server's
 * `ScreenListItem`: id, current published version (null if never published), updated-at, and a
 * `hasDraft` flag we use to power the Drafts shortcut.
 */
@Serializable
public data class ScreenSummary(
    val id: String,
    val currentVersion: Int? = null,
    val updatedAt: String,
    val hasDraft: Boolean = false,
)

@Serializable
internal data class ScreenDetailResponse(
    val id: String,
    val version: Int,
    val body: JsonElement,
    val publishedAt: String? = null,
    val editorEmail: String,
)

/**
 * Detailed published-screen payload. [body] is a pretty-printed JSON string of a `Screen`,
 * suitable for direct display in the editor pane. The editor decodes via
 * `SduiJson.decodeFromString(Screen.serializer(), body)` and feeds it into `staticScreenSource`
 * for `SduiHost` to render.
 */
public data class ScreenDetail(
    val id: String,
    val version: Int,
    val body: String,
    val publishedAt: String?,
    val editorEmail: String,
)

@Serializable
internal data class DraftResponse(
    val id: String,
    val screenId: String,
    val body: JsonElement,
    val updatedAt: String,
)

/** A draft pulled from the server, body pretty-printed. */
public data class DraftDetail(
    val id: String,
    val screenId: String,
    val body: String,
    val updatedAt: String,
)

/** Result of [StudioApi.putDraft] — explicit branches so the UI can inline-render violations. */
public sealed interface DraftSaveResult {
    /** 200 — server accepted the body and echoed back the canonical form. */
    public data class Saved(val draft: DraftDetail) : DraftSaveResult

    /** 400 — server rejected the body; [violations] are user-facing messages. */
    public data class Invalid(val violations: List<String>) : DraftSaveResult

    /** Any other non-2xx status. */
    public data class Failure(val statusCode: Int) : DraftSaveResult
}

@Serializable
internal data class PublishResponse(
    val screenId: String,
    val version: Int,
    val publishedAt: String,
)

/** Result of [StudioApi.publish] / [StudioApi.revert]. */
public sealed interface PublishResult {
    public data class Ok(val version: Int, val publishedAt: String) : PublishResult

    /** No draft to publish (publish only). */
    public data object NoDraft : PublishResult

    /** Caller's role does not grant the action (revert is admin-only). */
    public data object Forbidden : PublishResult

    public data class Failure(val statusCode: Int) : PublishResult
}

/** A row in the version history, mirroring the server's `VersionListItem`. */
@Serializable
public data class ScreenVersion(
    val version: Int,
    val createdAt: String,
    val publishedAt: String? = null,
    val editorId: String,
)

/** A single audit-log entry, mirroring the server's `AuditListItem`. */
@Serializable
public data class AuditEntry(
    val id: String,
    val screenId: String,
    val editorId: String,
    val action: String,
    val fromVersion: Int? = null,
    val toVersion: Int? = null,
    val at: String,
    val requestId: String,
)

// ---- M-S6: experiments + audiences DTOs (mirror of studio-server's ExperimentDtos) -----------

/** Listing item from `/experiments`. Mirrors `ExperimentDto` server-side. */
@Serializable
public data class ExperimentSummary(
    val id: String,
    val screenId: String,
    val name: String,
    val description: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val createdBy: String,
)

/** Variant view. Mirrors `VariantDto`. */
@Serializable
public data class VariantSummary(
    val id: String,
    val experimentId: String,
    val name: String,
    val weight: Int,
    val screenVersionId: String,
    val createdAt: String,
)

/** Audience view. The `predicate` field is forwarded as raw JSON for the editor preview. */
@Serializable
public data class AudienceSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val predicate: JsonElement,
    val createdAt: String,
)

/** Variant count from `/experiments/{id}/results`. */
@Serializable
public data class VariantCount(
    val variantId: String,
    val count: Long,
)
