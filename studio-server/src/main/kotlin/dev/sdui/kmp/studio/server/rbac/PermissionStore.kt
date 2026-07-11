package dev.sdui.kmp.studio.server.rbac

import dev.sdui.kmp.studio.server.db.EditorRoles
import dev.sdui.kmp.studio.server.db.Permissions
import dev.sdui.kmp.studio.server.db.RolePermissions
import dev.sdui.kmp.studio.server.db.Roles
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

/** A single role with its current permission set. */
public data class RoleRow(
    public val id: String,
    public val name: String,
    public val description: String,
    public val isSystem: Boolean,
    public val permissions: Set<String>,
)

/** A single permission as it appears in `/admin/permissions`. */
public data class PermissionRow(
    public val id: String,
    public val description: String,
)

/** A role assigned to an editor account. */
public data class EditorRoleAssignment(
    public val editorId: UUID,
    public val roleId: String,
)

/**
 * Read-mostly access to roles, permissions, and per-editor permission resolution.
 *
 * The hot path — `hasPermission(editorId, token)` — is a single join query: union the
 * permissions across every role the editor has, then check membership. We deliberately do not
 * cache here because:
 *
 *  1. Permission grants change rarely; the DB is fast enough on Postgres + H2.
 *  2. A cache layer adds invalidation complexity that is easy to get wrong (revoking a role
 *     for a logged-in editor must take effect immediately).
 *
 * If profiling later shows this is hot, the cache lives behind the same interface and tests
 * keep passing.
 */
public object PermissionStore {

    /** Whether the given editor has [permission] via at least one of their roles. */
    public suspend fun hasPermission(editorId: UUID, permission: String): Boolean = newSuspendedTransaction {
        val roleIds = EditorRoles
            .selectAll()
            .where { EditorRoles.editorId eq editorId }
            .map { it[EditorRoles.roleId] }
        if (roleIds.isEmpty()) {
            false
        } else {
            RolePermissions
                .selectAll()
                .where {
                    (RolePermissions.roleId inList roleIds) and
                        (RolePermissions.permissionId eq permission)
                }
                .empty()
                .not()
        }
    }

    /** Every permission the editor has, regardless of which role granted it. */
    public suspend fun permissionsFor(editorId: UUID): Set<String> = newSuspendedTransaction {
        val roleIds = EditorRoles
            .selectAll()
            .where { EditorRoles.editorId eq editorId }
            .map { it[EditorRoles.roleId] }
        if (roleIds.isEmpty()) {
            emptySet()
        } else {
            RolePermissions
                .selectAll()
                .where { RolePermissions.roleId inList roleIds }
                .map { it[RolePermissions.permissionId] }
                .toSet()
        }
    }

    /** Roles assigned to this editor. */
    public suspend fun rolesFor(editorId: UUID): List<String> = newSuspendedTransaction {
        EditorRoles
            .selectAll()
            .where { EditorRoles.editorId eq editorId }
            .map { it[EditorRoles.roleId] }
    }

    /** List every defined permission. */
    public suspend fun listPermissions(): List<PermissionRow> = newSuspendedTransaction {
        Permissions
            .selectAll()
            .map { PermissionRow(it[Permissions.id], it[Permissions.description]) }
    }

    /** List every role with its current permission set. */
    public suspend fun listRoles(): List<RoleRow> = newSuspendedTransaction {
        val perms = RolePermissions
            .selectAll()
            .groupBy({ it[RolePermissions.roleId] }, { it[RolePermissions.permissionId] })
        Roles
            .selectAll()
            .map { row ->
                val id = row[Roles.id]
                RoleRow(
                    id = id,
                    name = row[Roles.name],
                    description = row[Roles.description],
                    isSystem = row[Roles.isSystem],
                    permissions = perms[id]?.toSet().orEmpty(),
                )
            }
    }

    /** Lookup a single role with its permissions. */
    public suspend fun getRole(roleId: String): RoleRow? = newSuspendedTransaction {
        val row = Roles
            .selectAll()
            .where { Roles.id eq roleId }
            .limit(1)
            .firstOrNull() ?: return@newSuspendedTransaction null
        val perms = RolePermissions
            .selectAll()
            .where { RolePermissions.roleId eq roleId }
            .map { it[RolePermissions.permissionId] }
            .toSet()
        RoleRow(
            id = row[Roles.id],
            name = row[Roles.name],
            description = row[Roles.description],
            isSystem = row[Roles.isSystem],
            permissions = perms,
        )
    }

    /**
     * Create a custom role. Returns false if the id already exists. Caller is responsible for
     * rejecting attempts to create system role ids.
     */
    public suspend fun createRole(
        id: String,
        name: String,
        description: String,
        permissions: Collection<String>,
    ): Boolean = newSuspendedTransaction {
        val exists = !Roles.selectAll().where { Roles.id eq id }.limit(1).empty()
        if (exists) return@newSuspendedTransaction false
        Roles.insertIgnore {
            it[Roles.id] = id
            it[Roles.name] = name
            it[Roles.description] = description
            it[isSystem] = false
        }
        permissions.forEach { perm ->
            RolePermissions.insertIgnore {
                it[roleId] = id
                it[permissionId] = perm
            }
        }
        true
    }

    /**
     * Replace the permissions linked to [roleId] with [permissions]. Idempotent. Used by both
     * the admin update endpoint and the system-role bootstrap to keep system roles in sync
     * with [SystemRoles] across restarts.
     */
    @Suppress("SwallowedException")
    public suspend fun setRolePermissions(roleId: String, permissions: Collection<String>) {
        newSuspendedTransaction {
            RolePermissions.deleteWhere { RolePermissions.roleId eq roleId }
            permissions.forEach { perm ->
                RolePermissions.insertIgnore {
                    it[RolePermissions.roleId] = roleId
                    it[permissionId] = perm
                }
            }
        }
    }

    /** Delete a role. Returns false if it didn't exist or is a system role. */
    public suspend fun deleteRole(roleId: String): Boolean = newSuspendedTransaction {
        val row = Roles
            .selectAll()
            .where { Roles.id eq roleId }
            .limit(1)
            .firstOrNull() ?: return@newSuspendedTransaction false
        if (row[Roles.isSystem]) return@newSuspendedTransaction false
        // Cascade — permission links + editor links must go too. Foreign keys are app-level
        // here so we do it explicitly.
        RolePermissions.deleteWhere { RolePermissions.roleId eq roleId }
        EditorRoles.deleteWhere { EditorRoles.roleId eq roleId }
        Roles.deleteWhere { Roles.id eq roleId }
        true
    }

    /** Assign a role to an editor. Idempotent. Returns false if the role doesn't exist. */
    public suspend fun assignRole(editorId: UUID, roleId: String): Boolean = newSuspendedTransaction {
        val roleExists = !Roles
            .selectAll()
            .where { Roles.id eq roleId }
            .limit(1)
            .empty()
        if (!roleExists) return@newSuspendedTransaction false
        EditorRoles.insertIgnore {
            it[EditorRoles.editorId] = editorId
            it[EditorRoles.roleId] = roleId
        }
        true
    }

    /** Revoke a role from an editor. Idempotent — returns false only if no row matched. */
    public suspend fun revokeRole(editorId: UUID, roleId: String): Boolean = newSuspendedTransaction {
        val deleted = EditorRoles.deleteWhere {
            (EditorRoles.editorId eq editorId) and (EditorRoles.roleId eq roleId)
        }
        deleted > 0
    }

    /** Capture an audit-visible Instant for the role-mutation row. Reserved for future use. */
    @Suppress("UnusedPrivateMember")
    private fun nowFloor(): Instant = Instant.now()
}
