package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.studio.server.auth.editorPrincipal
import dev.sdui.kmp.studio.server.db.AuditAction
import dev.sdui.kmp.studio.server.db.AuditStore
import dev.sdui.kmp.studio.server.db.EditorAccountStore
import dev.sdui.kmp.studio.server.db.ScreenStore
import dev.sdui.kmp.studio.server.model.DraftResponse
import dev.sdui.kmp.studio.server.model.ErrorResponse
import dev.sdui.kmp.studio.server.model.PublishResponse
import dev.sdui.kmp.studio.server.model.ScreenDetailResponse
import dev.sdui.kmp.studio.server.model.ScreenListItem
import dev.sdui.kmp.studio.server.rbac.Permission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * Mounts every screen-management route under `/admin/screens/...`. Every route here lives
 * inside [authenticate] (the JWT verifier) and additionally calls [requirePermission] to gate
 * on a granular permission token. M-S7 replaced the legacy
 * [dev.sdui.kmp.studio.server.db.EditorRole]-based [requireRole] guard with this finer-grained
 * model — see `docs/adr/0020-studio-rbac-permission-model.md`.
 *
 * Routes intentionally call straight through to [ScreenStore] / [AuditStore] — the route
 * layer is thin orchestration; transactional integrity is the store's job.
 */
@Suppress("LongMethod", "ComplexMethod", "CyclomaticComplexMethod")
public fun Route.installScreensAdminRoutes(
    jwtAuthName: String,
    notifier: PublishNotifier,
    validator: DraftValidator,
) {
    authenticate(jwtAuthName) {
        get("/admin/screens") {
            call.requirePermission(Permission.SCREENS_READ) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val screens = ScreenStore.listScreens(forEditor = principal.editorId).map { row ->
                    ScreenListItem(
                        id = row.screenId,
                        currentVersion = row.currentVersion,
                        updatedAt = row.updatedAt.toString(),
                        hasDraft = row.hasDraft,
                    )
                }
                call.respond(HttpStatusCode.OK, screens)
            }
        }

        get("/admin/screens/{id}") {
            call.requirePermission(Permission.SCREENS_READ) {
                val id = call.parameters["id"].orEmpty()
                val current = ScreenStore.currentVersion(id)
                if (current == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("screen not found: $id"))
                    return@requirePermission
                }
                val authorEmail = EditorAccountStore.findById(current.editorId)?.email ?: "unknown"
                call.respond(
                    HttpStatusCode.OK,
                    ScreenDetailResponse(
                        id = id,
                        version = current.versionNumber,
                        body = parseJsonBody(current.bodyJson),
                        publishedAt = current.publishedAt?.toString(),
                        editorEmail = authorEmail,
                    ),
                )
            }
        }

        get("/admin/screens/{id}/draft") {
            call.requirePermission(Permission.DRAFTS_READ) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val id = call.parameters["id"].orEmpty()
                val draft = ScreenStore.draftFor(id, principal.editorId)
                if (draft == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no draft for screen: $id"))
                    return@requirePermission
                }
                call.respond(
                    HttpStatusCode.OK,
                    DraftResponse(
                        id = draft.id.toString(),
                        screenId = id,
                        body = parseJsonBody(draft.bodyJson),
                        updatedAt = draft.updatedAt.toString(),
                    ),
                )
            }
        }

        put("/admin/screens/{id}/draft") {
            call.requirePermission(Permission.DRAFTS_UPDATE) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val id = call.parameters["id"].orEmpty()
                val body = try {
                    call.receive<JsonElement>()
                } catch (_: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed JSON body"))
                    return@requirePermission
                }
                when (val verdict = validator.validate(body)) {
                    is DraftValidationResult.Invalid -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("draft is not a valid Screen", verdict.violations),
                        )
                    }
                    is DraftValidationResult.Valid -> {
                        val saved = ScreenStore.upsertDraft(id, principal.editorId, verdict.canonicalJson)
                        AuditStore.append(
                            screenId = id,
                            editorId = principal.editorId,
                            action = AuditAction.Drafted,
                            requestId = call.requestIdOrFallback(),
                            actorIp = call.actorIp(),
                            userAgent = call.actorUserAgent(),
                        )
                        call.respond(
                            HttpStatusCode.OK,
                            DraftResponse(
                                id = saved.id.toString(),
                                screenId = id,
                                body = parseJsonBody(saved.bodyJson),
                                updatedAt = saved.updatedAt.toString(),
                            ),
                        )
                    }
                }
            }
        }

        post("/admin/screens/{id}/publish") {
            call.requirePermission(Permission.VERSIONS_PUBLISH) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val id = call.parameters["id"].orEmpty()
                val draft = ScreenStore.draftFor(id, principal.editorId)
                if (draft == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no draft to publish for screen: $id"))
                    return@requirePermission
                }
                val published = ScreenStore.publishDraft(id, principal.editorId)
                AuditStore.append(
                    screenId = id,
                    editorId = principal.editorId,
                    action = AuditAction.Published,
                    fromVersion = published.versionNumber - 1,
                    toVersion = published.versionNumber,
                    requestId = call.requestIdOrFallback(),
                    actorIp = call.actorIp(),
                    userAgent = call.actorUserAgent(),
                )
                // Fire the notifier OUTSIDE the transaction; failures must not roll back the publish.
                runCatching { notifier.screenPublished(id, published.versionNumber, published.bodyJson) }
                call.respond(
                    HttpStatusCode.OK,
                    PublishResponse(
                        screenId = id,
                        version = published.versionNumber,
                        publishedAt = published.publishedAt?.toString().orEmpty(),
                    ),
                )
            }
        }

        post("/admin/screens/{id}/revert") {
            call.requirePermission(Permission.VERSIONS_REVERT) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val id = call.parameters["id"].orEmpty()
                val to = call.request.queryParameters["to"]?.toIntOrNull()
                if (to == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing or invalid ?to=<version>"))
                    return@requirePermission
                }
                val source = ScreenStore.versionByNumber(id, to)
                if (source == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("source version $to not found"))
                    return@requirePermission
                }
                val newVersion = ScreenStore.revertTo(id, to, principal.editorId)
                AuditStore.append(
                    screenId = id,
                    editorId = principal.editorId,
                    action = AuditAction.Reverted,
                    fromVersion = to,
                    toVersion = newVersion.versionNumber,
                    requestId = call.requestIdOrFallback(),
                    actorIp = call.actorIp(),
                    userAgent = call.actorUserAgent(),
                )
                runCatching { notifier.screenPublished(id, newVersion.versionNumber, newVersion.bodyJson) }
                call.respond(
                    HttpStatusCode.OK,
                    PublishResponse(
                        screenId = id,
                        version = newVersion.versionNumber,
                        publishedAt = newVersion.publishedAt?.toString().orEmpty(),
                    ),
                )
            }
        }

        delete("/admin/screens/{id}") {
            call.requirePermission(Permission.SCREENS_DELETE) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val id = call.parameters["id"].orEmpty()
                ScreenStore.softDelete(id)
                AuditStore.append(
                    screenId = id,
                    editorId = principal.editorId,
                    action = AuditAction.Deleted,
                    requestId = call.requestIdOrFallback(),
                    actorIp = call.actorIp(),
                    userAgent = call.actorUserAgent(),
                )
                call.respond(HttpStatusCode.OK, mapOf("deleted" to id))
            }
        }
    }
}

private fun parseJsonBody(raw: String): JsonElement =
    dev.sdui.kmp.protocol.SduiJson.parseToJsonElement(raw)

/**
 * Legacy session-aware principal resolver. Retained for callers that haven't migrated to the
 * new [requirePermission] / [livePrincipal] pair (e.g. [installScreenAssignRoute] for the
 * unauthenticated assign surface). New code should use [requirePermission] instead.
 */
internal suspend fun ApplicationCall.requireLivePrincipal(): dev.sdui.kmp.studio.server.auth.EditorPrincipal? {
    val principal = editorPrincipal()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid principal"))
        return null
    }
    if (!dev.sdui.kmp.studio.server.db.EditorSessionStore.isLive(principal.sessionId)) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("session revoked or expired"))
        return null
    }
    return principal
}

internal fun ApplicationCall.requestIdOrFallback(): String =
    request.headers["X-Request-Id"] ?: "studio-${java.util.UUID.randomUUID()}"
