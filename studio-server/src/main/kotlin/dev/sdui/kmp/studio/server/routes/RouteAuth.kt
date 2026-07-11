package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.studio.server.auth.EditorPrincipal
import dev.sdui.kmp.studio.server.auth.editorPrincipal
import dev.sdui.kmp.studio.server.db.EditorSessionStore
import dev.sdui.kmp.studio.server.model.ErrorResponse
import dev.sdui.kmp.studio.server.rbac.PermissionStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond

/**
 * Authentication / authorization helpers shared by every Studio route.
 *
 * `requirePermission(permission, block)` is the post-M-S7 replacement for the legacy
 * `requireRole(min)` / `EditorRole`-based gates. It performs three checks in order:
 *
 *  1. [editorPrincipal] resolves the JWT into an [EditorPrincipal]. If the call is
 *     unauthenticated the helper responds 401 and returns without invoking [block].
 *  2. [EditorSessionStore.isLive] verifies the session row hasn't been revoked or expired
 *     (the JWT verifier handles `exp` but we revoke on logout via the session row).
 *  3. [PermissionStore.hasPermission] queries the `editor_roles` × `role_permissions` join.
 *     Missing → 403; present → invoke [block].
 *
 * Inside [block], handlers usually need the editor id to attribute audit / draft rows. Use
 * [livePrincipal] to fetch it after the gate has passed:
 *
 * ```
 * delete("/admin/screens/{id}") {
 *     call.requirePermission(Permission.SCREENS_DELETE) {
 *         val principal = call.livePrincipal()!! // safe — gate validated it
 *         // ...
 *     }
 * }
 * ```
 */
public suspend inline fun ApplicationCall.requirePermission(
    permission: String,
    block: () -> Unit,
) {
    val principal = editorPrincipal()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid principal"))
        return
    }
    if (!EditorSessionStore.isLive(principal.sessionId)) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("session revoked or expired"))
        return
    }
    if (!PermissionStore.hasPermission(principal.editorId, permission)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("missing permission: $permission"))
        return
    }
    block()
}

/**
 * Resolve the call's [EditorPrincipal] without performing the permission check. Use this
 * inside a [requirePermission] block to fetch attribution data for audit rows. Returns null
 * for unauthenticated calls (which the route's [requirePermission] gate would have already
 * rejected — so callers can `!!` safely inside the block).
 */
public fun ApplicationCall.livePrincipal(): EditorPrincipal? = editorPrincipal()

/**
 * Capture the calling client's IP address. Returns null on test harnesses that don't go
 * through a real socket. Honours the [io.ktor.server.plugins.origin] plugin's RFC-7239
 * resolution if installed; otherwise falls back to the raw remote host.
 */
public fun ApplicationCall.actorIp(): String? = runCatching {
    request.origin.remoteHost.takeIf { it.isNotBlank() && it != "unknown" }
}.getOrNull()

/** Capture the calling client's `User-Agent` header. */
public fun ApplicationCall.actorUserAgent(): String? =
    request.headers[HttpHeaders.UserAgent]?.takeIf { it.isNotBlank() }
