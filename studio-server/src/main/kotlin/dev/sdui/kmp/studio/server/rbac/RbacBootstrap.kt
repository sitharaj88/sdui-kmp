package dev.sdui.kmp.studio.server.rbac

import dev.sdui.kmp.studio.server.db.EditorAccounts
import dev.sdui.kmp.studio.server.db.EditorRoles
import dev.sdui.kmp.studio.server.db.Permissions
import dev.sdui.kmp.studio.server.db.RolePermissions
import dev.sdui.kmp.studio.server.db.Roles
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Idempotent bootstrap for the granular RBAC tables.
 *
 *  - Inserts every catalogued permission ([Permission.All]) into [Permissions].
 *  - Creates the three system roles (`admin`, `editor`, `viewer`) in [Roles].
 *  - Resyncs the system-role permission sets from [SystemRoles] — a redeploy that adds a new
 *    permission picks it up on the admin role automatically.
 *  - Migrates any [EditorAccounts.role] string into the [EditorRoles] join table.
 *
 * "Idempotent" means: running [bootstrap] N times leaves the database in the same state as
 * running it once. The implementation uses Exposed's `insertIgnore` (= INSERT … ON CONFLICT
 * DO NOTHING) plus a delete-then-reinsert for the system-role permission sets so additions
 * to the catalogue propagate without leaving stale rows.
 *
 * Called from [dev.sdui.kmp.studio.server.db.StudioDatabase.connect] so every studio boot
 * arrives in a known state, including the in-memory H2 used by tests.
 */
public object RbacBootstrap {

    /** Run the seed + legacy migration. Safe to call multiple times. */
    public fun bootstrap() {
        transaction {
            seedPermissions()
            seedSystemRoles()
            syncSystemRolePermissions()
            migrateLegacyRolesSync()
        }
    }

    private fun seedPermissions() {
        Permission.Descriptions.forEach { (id, description) ->
            Permissions.insertIgnore {
                it[Permissions.id] = id
                it[Permissions.description] = description
            }
        }
    }

    private fun seedSystemRoles() {
        seedRole(SystemRoles.ADMIN, "Administrator", "Full access including user and role management.")
        seedRole(SystemRoles.EDITOR, "Editor", "Can manage screens, drafts, versions, and experiments.")
        seedRole(SystemRoles.VIEWER, "Viewer", "Read-only access to screens, audit, and experiments.")
    }

    private fun seedRole(id: String, name: String, description: String) {
        Roles.insertIgnore {
            it[Roles.id] = id
            it[Roles.name] = name
            it[Roles.description] = description
            it[isSystem] = true
        }
    }

    private fun syncSystemRolePermissions() {
        // For each system role: replace its permission set with the catalogue-derived target so
        // additions to [SystemRoles.AdminPermissions] propagate on next deploy. Idempotent —
        // identical calls yield identical state.
        SystemRoles.SystemRoleIds.forEach { systemRoleId ->
            val target = SystemRoles.permissionsFor(systemRoleId).toSet()
            RolePermissions.deleteWhere { RolePermissions.roleId eq systemRoleId }
            target.forEach { perm ->
                RolePermissions.insertIgnore {
                    it[roleId] = systemRoleId
                    it[permissionId] = perm
                }
            }
        }
    }

    /**
     * Migrate every [EditorAccounts.role] string into the [EditorRoles] join. Idempotent —
     * the join's PK collapses duplicate inserts, and [insertIgnore] is a no-op when the row
     * already exists.
     */
    private fun migrateLegacyRolesSync() {
        EditorAccounts
            .selectAll()
            .forEach { row ->
                val accountId = row[EditorAccounts.id]
                val legacy = row[EditorAccounts.role].lowercase()
                val targetRoleId = when (legacy) {
                    SystemRoles.ADMIN -> SystemRoles.ADMIN
                    SystemRoles.EDITOR -> SystemRoles.EDITOR
                    else -> SystemRoles.VIEWER
                }
                EditorRoles.insertIgnore {
                    it[editorId] = accountId
                    it[roleId] = targetRoleId
                }
            }
    }

    /**
     * After creating an editor with a legacy role string, callers (`EditorAccountStore.create`,
     * the new `/admin/editors` POST route, and tests) call this to assign the matching system
     * role immediately rather than waiting for the next startup migration.
     */
    public fun assignLegacyRoleSync(accountId: UUID, legacy: String) {
        transaction {
            val targetRoleId = when (legacy.lowercase()) {
                SystemRoles.ADMIN -> SystemRoles.ADMIN
                SystemRoles.EDITOR -> SystemRoles.EDITOR
                else -> SystemRoles.VIEWER
            }
            EditorRoles.insertIgnore {
                it[editorId] = accountId
                it[roleId] = targetRoleId
            }
        }
    }

    /** Bump the legacy [EditorAccounts.role] string for the given editor. */
    public fun setLegacyRoleColumnSync(accountId: UUID, legacy: String) {
        transaction {
            EditorAccounts.update({ EditorAccounts.id eq accountId }) {
                it[role] = legacy
            }
        }
    }
}
