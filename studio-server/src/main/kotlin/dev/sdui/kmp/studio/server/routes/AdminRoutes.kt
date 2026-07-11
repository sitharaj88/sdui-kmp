package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.studio.server.db.EditorAccountStore
import dev.sdui.kmp.studio.server.db.EditorRole
import dev.sdui.kmp.studio.server.model.ErrorResponse
import dev.sdui.kmp.studio.server.rbac.Permission
import dev.sdui.kmp.studio.server.rbac.PermissionStore
import dev.sdui.kmp.studio.server.rbac.SystemRoles
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.util.UUID

/**
 * `POST /admin/editors` request body. */
@Serializable
public data class CreateEditorRequest(
    public val email: String,
    public val password: String,
    public val displayName: String,
    /** Legacy role string for the new editor; granular roles are assigned via dedicated routes. */
    public val role: String = SystemRoles.VIEWER,
)

/** `PATCH /admin/editors/{id}` request body. */
@Serializable
public data class UpdateEditorRequest(
    public val displayName: String? = null,
    /** New legacy role label, e.g. `editor`. Updates the legacy column AND the [editor_roles] join. */
    public val role: String? = null,
)

/** `GET /admin/editors` listing item. */
@Serializable
public data class EditorListItem(
    public val id: String,
    public val email: String,
    public val displayName: String,
    public val role: String,
    public val roles: List<String>,
    public val permissions: List<String>,
)

/** `POST /admin/roles` request body. */
@Serializable
public data class CreateRoleRequest(
    public val id: String,
    public val name: String,
    public val description: String = "",
    public val permissions: List<String> = emptyList(),
)

/** `PATCH /admin/roles/{id}/permissions` request body. */
@Serializable
public data class SetRolePermissionsRequest(public val permissions: List<String>)

/** `GET /admin/roles` and `GET /admin/roles/{id}` response item. */
@Serializable
public data class RoleListItem(
    public val id: String,
    public val name: String,
    public val description: String,
    public val isSystem: Boolean,
    public val permissions: List<String>,
)

/** `GET /admin/permissions` listing item. */
@Serializable
public data class PermissionListItem(
    public val id: String,
    public val description: String,
)

/**
 * Mounts editor / role / permission admin routes under `/admin/...`.
 *
 * Routes:
 *  - `GET    /admin/editors`                                         (`editors:read`)
 *  - `POST   /admin/editors`                                         (`editors:create`)
 *  - `PATCH  /admin/editors/{id}`                                    (`editors:update`)
 *  - `DELETE /admin/editors/{id}`                                    (`editors:delete`)
 *  - `POST   /admin/editors/{id}/roles/{roleId}`                     (`editors:update`)
 *  - `DELETE /admin/editors/{id}/roles/{roleId}`                     (`editors:update`)
 *  - `GET    /admin/roles`                                           (`roles:read`)
 *  - `GET    /admin/roles/{id}`                                      (`roles:read`)
 *  - `POST   /admin/roles`                                           (`roles:create`)
 *  - `PATCH  /admin/roles/{id}/permissions`                          (`roles:update`)
 *  - `DELETE /admin/roles/{id}`                                      (`roles:delete`)
 *  - `GET    /admin/permissions`                                     (`roles:read`)
 *
 * Custom (non-system) roles can be created, updated, and deleted; system roles are read-only.
 * The route layer rejects writes to system role ids with 400 — see [SystemRoles.SystemRoleIds].
 */
@Suppress("LongMethod", "ComplexMethod", "CyclomaticComplexMethod")
public fun Route.installAdminRoutes(jwtAuthName: String) {
    authenticate(jwtAuthName) {
        get("/admin/editors") {
            call.requirePermission(Permission.EDITORS_READ) {
                val editors = EditorAccountStore.list().map { acc ->
                    val roles = PermissionStore.rolesFor(acc.id)
                    val perms = PermissionStore.permissionsFor(acc.id)
                    EditorListItem(
                        id = acc.id.toString(),
                        email = acc.email,
                        displayName = acc.displayName,
                        role = acc.role.wire,
                        roles = roles,
                        permissions = perms.toList().sorted(),
                    )
                }
                call.respond(HttpStatusCode.OK, editors)
            }
        }

        post("/admin/editors") {
            call.requirePermission(Permission.EDITORS_CREATE) {
                val req = call.receiveOrBadRequest<CreateEditorRequest>() ?: return@requirePermission
                if (req.email.isBlank() || req.password.isBlank() || req.displayName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("email, password, displayName required"))
                    return@requirePermission
                }
                if (EditorAccountStore.findByEmail(req.email.trim().lowercase()) != null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("email already exists"))
                    return@requirePermission
                }
                val role = EditorRole.parse(req.role)
                val account = EditorAccountStore.create(
                    email = req.email.trim().lowercase(),
                    plaintextPassword = req.password,
                    displayName = req.displayName,
                    role = role,
                )
                call.respond(
                    HttpStatusCode.Created,
                    EditorListItem(
                        id = account.id.toString(),
                        email = account.email,
                        displayName = account.displayName,
                        role = account.role.wire,
                        roles = PermissionStore.rolesFor(account.id),
                        permissions = PermissionStore.permissionsFor(account.id).toList().sorted(),
                    ),
                )
            }
        }

        patch("/admin/editors/{id}") {
            call.requirePermission(Permission.EDITORS_UPDATE) {
                val id = parseEditorIdParam(call) ?: return@requirePermission
                val req = call.receiveOrBadRequest<UpdateEditorRequest>() ?: return@requirePermission
                val existing = EditorAccountStore.findById(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("editor not found: $id"))
                    return@requirePermission
                }
                req.displayName?.takeIf { it.isNotBlank() }?.let {
                    EditorAccountStore.updateDisplayName(id, it)
                }
                req.role?.takeIf { it.isNotBlank() }?.let { roleString ->
                    val newRole = EditorRole.parse(roleString)
                    EditorAccountStore.updateRoleColumn(id, newRole.wire)
                    // Replace the editor's system-role assignments so the permission set
                    // tracks the new legacy label. Custom-role assignments are untouched.
                    EditorAccountStore.replaceSystemRole(id, newRole.wire)
                }
                val updated = EditorAccountStore.findById(id) ?: existing
                call.respond(
                    HttpStatusCode.OK,
                    EditorListItem(
                        id = updated.id.toString(),
                        email = updated.email,
                        displayName = updated.displayName,
                        role = updated.role.wire,
                        roles = PermissionStore.rolesFor(updated.id),
                        permissions = PermissionStore.permissionsFor(updated.id).toList().sorted(),
                    ),
                )
            }
        }

        delete("/admin/editors/{id}") {
            call.requirePermission(Permission.EDITORS_DELETE) {
                val id = parseEditorIdParam(call) ?: return@requirePermission
                if (EditorAccountStore.findById(id) == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("editor not found: $id"))
                    return@requirePermission
                }
                EditorAccountStore.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to id.toString()))
            }
        }

        post("/admin/editors/{id}/roles/{roleId}") {
            call.requirePermission(Permission.EDITORS_UPDATE) {
                val id = parseEditorIdParam(call) ?: return@requirePermission
                val roleId = call.parameters["roleId"].orEmpty()
                if (EditorAccountStore.findById(id) == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("editor not found"))
                    return@requirePermission
                }
                val ok = PermissionStore.assignRole(id, roleId)
                if (!ok) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("role not found: $roleId"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.OK, mapOf("editorId" to id.toString(), "roleId" to roleId))
            }
        }

        delete("/admin/editors/{id}/roles/{roleId}") {
            call.requirePermission(Permission.EDITORS_UPDATE) {
                val id = parseEditorIdParam(call) ?: return@requirePermission
                val roleId = call.parameters["roleId"].orEmpty()
                PermissionStore.revokeRole(id, roleId)
                call.respond(HttpStatusCode.OK, mapOf("editorId" to id.toString(), "roleId" to roleId))
            }
        }

        get("/admin/roles") {
            call.requirePermission(Permission.ROLES_READ) {
                val roles = PermissionStore.listRoles().map { it.toListItem() }
                call.respond(HttpStatusCode.OK, roles)
            }
        }

        get("/admin/roles/{id}") {
            call.requirePermission(Permission.ROLES_READ) {
                val id = call.parameters["id"].orEmpty()
                val role = PermissionStore.getRole(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("role not found: $id"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.OK, role.toListItem())
            }
        }

        post("/admin/roles") {
            call.requirePermission(Permission.ROLES_CREATE) {
                val req = call.receiveOrBadRequest<CreateRoleRequest>() ?: return@requirePermission
                if (req.id.isBlank() || req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("id and name are required"))
                    return@requirePermission
                }
                if (req.id in SystemRoles.SystemRoleIds) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot redefine a system role: ${req.id}"))
                    return@requirePermission
                }
                val unknown = req.permissions - PermissionStore.listPermissions().map { it.id }.toSet()
                if (unknown.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown permissions: $unknown"))
                    return@requirePermission
                }
                val created = PermissionStore.createRole(req.id, req.name, req.description, req.permissions)
                if (!created) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("role id already exists: ${req.id}"))
                    return@requirePermission
                }
                val full = PermissionStore.getRole(req.id) ?: run {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("role vanished after creation"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.Created, full.toListItem())
            }
        }

        patch("/admin/roles/{id}/permissions") {
            call.requirePermission(Permission.ROLES_UPDATE) {
                val id = call.parameters["id"].orEmpty()
                if (id in SystemRoles.SystemRoleIds) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("cannot edit permissions of a system role: $id"),
                    )
                    return@requirePermission
                }
                val req = call.receiveOrBadRequest<SetRolePermissionsRequest>() ?: return@requirePermission
                val role = PermissionStore.getRole(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("role not found: $id"))
                    return@requirePermission
                }
                val unknown = req.permissions - PermissionStore.listPermissions().map { it.id }.toSet()
                if (unknown.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown permissions: $unknown"))
                    return@requirePermission
                }
                PermissionStore.setRolePermissions(role.id, req.permissions)
                val refreshed = PermissionStore.getRole(role.id) ?: run {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("role vanished after update"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.OK, refreshed.toListItem())
            }
        }

        delete("/admin/roles/{id}") {
            call.requirePermission(Permission.ROLES_DELETE) {
                val id = call.parameters["id"].orEmpty()
                if (id in SystemRoles.SystemRoleIds) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot delete a system role: $id"))
                    return@requirePermission
                }
                val deleted = PermissionStore.deleteRole(id)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("role not found: $id"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.OK, mapOf("deleted" to id))
            }
        }

        get("/admin/permissions") {
            call.requirePermission(Permission.ROLES_READ) {
                val perms = PermissionStore.listPermissions().map {
                    PermissionListItem(id = it.id, description = it.description)
                }
                call.respond(HttpStatusCode.OK, perms)
            }
        }
    }
}

private fun dev.sdui.kmp.studio.server.rbac.RoleRow.toListItem(): RoleListItem = RoleListItem(
    id = id,
    name = name,
    description = description,
    isSystem = isSystem,
    permissions = permissions.toList().sorted(),
)

private suspend fun parseEditorIdParam(call: ApplicationCall): UUID? {
    val raw = call.parameters["id"].orEmpty()
    return runCatching { UUID.fromString(raw) }.getOrNull().also {
        if (it == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid editor id UUID"))
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrBadRequest(): T? = try {
    receive<T>()
} catch (_: SerializationException) {
    respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request body"))
    null
} catch (_: io.ktor.server.plugins.ContentTransformationException) {
    respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request body"))
    null
}
