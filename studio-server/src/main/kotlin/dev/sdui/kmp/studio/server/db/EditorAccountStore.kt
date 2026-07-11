package dev.sdui.kmp.studio.server.db

import at.favre.lib.crypto.bcrypt.BCrypt
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/** Read-side projection of [EditorAccounts]. */
public data class EditorAccount(
    public val id: UUID,
    public val email: String,
    public val displayName: String,
    public val role: EditorRole,
    public val createdAt: Instant,
    public val lastLoginAt: Instant?,
)

/**
 * CRUD helpers for [EditorAccounts]. Bcrypt cost is fixed at 12 — the standard 2024-era
 * recommendation for interactive logins. Production deployments should make it
 * environment-tunable.
 */
public object EditorAccountStore {
    /**
     * Bcrypt cost factor for password hashes. Production deployments should set
     * `STUDIO_BCRYPT_COST` to 12 (≈250 ms per hash on a 2024-era laptop). Tests override this to
     * a low value (4) so a 13-test suite finishes in a few seconds instead of a minute.
     */
    private val BCRYPT_COST: Int = System.getenv("STUDIO_BCRYPT_COST")?.toIntOrNull() ?: 12

    /** Hash a plaintext password with bcrypt. Returns the salt-prefixed hash string. */
    public fun hashPassword(plaintext: String): String =
        BCrypt.withDefaults().hashToString(BCRYPT_COST, plaintext.toCharArray())

    /** Verify a plaintext password against a stored hash. */
    public fun verifyPassword(plaintext: String, hash: String): Boolean =
        BCrypt.verifyer().verify(plaintext.toCharArray(), hash).verified

    /**
     * Insert a new editor row. Returns the freshly-minted [EditorAccount].
     *
     * Also assigns the matching system role in `editor_roles` so the editor has the granular
     * permissions for the legacy role string immediately — without this, a fresh editor would
     * have no permissions until the next [dev.sdui.kmp.studio.server.rbac.RbacBootstrap.bootstrap]
     * sweep on restart.
     */
    public suspend fun create(
        email: String,
        plaintextPassword: String,
        displayName: String,
        role: EditorRole,
    ): EditorAccount = newSuspendedTransaction {
        val id = UUID.randomUUID()
        val createdAt = Instant.now()
        val hash = hashPassword(plaintextPassword)
        EditorAccounts.insert {
            it[EditorAccounts.id] = id
            it[EditorAccounts.email] = email
            it[EditorAccounts.passwordHash] = hash
            it[EditorAccounts.displayName] = displayName
            it[EditorAccounts.role] = role.wire
            it[EditorAccounts.createdAt] = createdAt
        }
        // Mirror into the granular RBAC join so freshly-created editors inherit the legacy
        // role's permission bundle without waiting for a server restart. Inlined (rather than
        // calling RbacBootstrap.assignLegacyRoleSync) so we stay inside the same Exposed
        // transaction; nesting `transaction { }` inside `newSuspendedTransaction { }` can
        // commit out of order on H2.
        val systemRoleId = when (role.wire) {
            dev.sdui.kmp.studio.server.rbac.SystemRoles.ADMIN ->
                dev.sdui.kmp.studio.server.rbac.SystemRoles.ADMIN
            dev.sdui.kmp.studio.server.rbac.SystemRoles.EDITOR ->
                dev.sdui.kmp.studio.server.rbac.SystemRoles.EDITOR
            else -> dev.sdui.kmp.studio.server.rbac.SystemRoles.VIEWER
        }
        dev.sdui.kmp.studio.server.db.EditorRoles.insertIgnore {
            it[editorId] = id
            it[roleId] = systemRoleId
        }
        EditorAccount(id, email, displayName, role, createdAt, null)
    }

    /** Lookup by email (case-sensitive — emails are normalised at the route boundary). */
    public suspend fun findByEmail(email: String): EditorAccount? = newSuspendedTransaction {
        EditorAccounts
            .selectAll()
            .where { EditorAccounts.email eq email }
            .limit(1)
            .firstOrNull()
            ?.toAccount()
    }

    /** Lookup by id; used by routes to materialise the calling editor. */
    public suspend fun findById(id: UUID): EditorAccount? = newSuspendedTransaction {
        EditorAccounts
            .selectAll()
            .where { EditorAccounts.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toAccount()
    }

    /**
     * Verify a login attempt. Returns the account on success, null on bad email or wrong
     * password (callers MUST NOT distinguish between the two so attackers can't probe email
     * existence).
     */
    public suspend fun authenticate(email: String, password: String): EditorAccount? {
        val row = newSuspendedTransaction {
            EditorAccounts
                .selectAll()
                .where { EditorAccounts.email eq email }
                .limit(1)
                .firstOrNull()
        } ?: return null
        val hash = row[EditorAccounts.passwordHash]
        if (!verifyPassword(password, hash)) return null
        return row.toAccount()
    }

    /** Stamp `last_login_at` after a successful login. */
    public suspend fun recordLogin(id: UUID) {
        newSuspendedTransaction {
            EditorAccounts.update({ EditorAccounts.id eq id }) {
                it[lastLoginAt] = Instant.now()
            }
        }
    }

    /** List every editor account. Used by `/admin/editors`. */
    public suspend fun list(): List<EditorAccount> = newSuspendedTransaction {
        EditorAccounts
            .selectAll()
            .orderBy(EditorAccounts.email, SortOrder.ASC)
            .map { it.toAccount() }
    }

    /** Update the display name of an editor. */
    public suspend fun updateDisplayName(id: UUID, displayName: String) {
        newSuspendedTransaction {
            EditorAccounts.update({ EditorAccounts.id eq id }) {
                it[EditorAccounts.displayName] = displayName
            }
        }
    }

    /** Update the legacy [EditorAccounts.role] string for an editor. */
    public suspend fun updateRoleColumn(id: UUID, legacy: String) {
        newSuspendedTransaction {
            EditorAccounts.update({ EditorAccounts.id eq id }) {
                it[role] = legacy
            }
        }
    }

    /**
     * Replace the editor's system-role assignments with the one matching [legacy]. Custom-role
     * assignments (anything not in [dev.sdui.kmp.studio.server.rbac.SystemRoles.SystemRoleIds])
     * are untouched. Used by `PATCH /admin/editors/{id}` when an admin bumps the legacy role.
     */
    public suspend fun replaceSystemRole(id: UUID, legacy: String) {
        newSuspendedTransaction {
            val targetRoleId = when (legacy.lowercase()) {
                dev.sdui.kmp.studio.server.rbac.SystemRoles.ADMIN ->
                    dev.sdui.kmp.studio.server.rbac.SystemRoles.ADMIN
                dev.sdui.kmp.studio.server.rbac.SystemRoles.EDITOR ->
                    dev.sdui.kmp.studio.server.rbac.SystemRoles.EDITOR
                else -> dev.sdui.kmp.studio.server.rbac.SystemRoles.VIEWER
            }
            // Clear existing system-role assignments only.
            dev.sdui.kmp.studio.server.db.EditorRoles.deleteWhere {
                (editorId eq id) and
                    (roleId inList dev.sdui.kmp.studio.server.rbac.SystemRoles.SystemRoleIds.toList())
            }
            dev.sdui.kmp.studio.server.db.EditorRoles.insertIgnore {
                it[editorId] = id
                it[roleId] = targetRoleId
            }
        }
    }

    /**
     * Delete an editor account. Cascades into [EditorRoles] (the join table is app-managed FK
     * here) and [EditorSessions] so a deleted operator's existing tokens immediately fail
     * the [EditorSessionStore.isLive] check.
     */
    public suspend fun delete(id: UUID) {
        newSuspendedTransaction {
            dev.sdui.kmp.studio.server.db.EditorRoles.deleteWhere {
                editorId eq id
            }
            EditorSessions.deleteWhere { EditorSessions.editorId eq id }
            EditorAccounts.deleteWhere { EditorAccounts.id eq id }
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toAccount(): EditorAccount = EditorAccount(
        id = this[EditorAccounts.id],
        email = this[EditorAccounts.email],
        displayName = this[EditorAccounts.displayName],
        role = EditorRole.parse(this[EditorAccounts.role]),
        createdAt = this[EditorAccounts.createdAt],
        lastLoginAt = this[EditorAccounts.lastLoginAt],
    )
}
