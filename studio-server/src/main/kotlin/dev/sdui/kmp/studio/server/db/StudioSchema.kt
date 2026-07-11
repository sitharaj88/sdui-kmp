package dev.sdui.kmp.studio.server.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant
import java.util.UUID

/**
 * Editor accounts for the Studio backend. `email` is unique and used as the login key.
 * `password_hash` stores a bcrypt hash (never a plaintext password).
 *
 * `role` is the legacy free-text role label (`admin` / `editor` / `viewer`). M-S7 introduced
 * granular permissions — see [Roles], [Permissions], [RolePermissions], [EditorRoles]. The
 * legacy column is preserved for one transition period so existing tokens and the
 * `EditorAccount.role` projection continue to round-trip; new code should consult
 * [dev.sdui.kmp.studio.server.rbac.PermissionStore.hasPermission] instead. See
 * `docs/adr/0020-studio-rbac-permission-model.md` for the removal plan.
 *
 * Schema mirrors `studio-server/db/migrations/V1__studio_initial.sql` — keep both in lockstep.
 */
public object EditorAccounts : Table("editor_accounts") {
    public val id: Column<UUID> = uuid("id")
    public val email: Column<String> = varchar("email", EMAIL_LEN).uniqueIndex()
    public val passwordHash: Column<String> = varchar("password_hash", HASH_LEN)
    public val displayName: Column<String> = varchar("display_name", DISPLAY_NAME_LEN)
    public val role: Column<String> = varchar("role", ROLE_LEN)
    public val createdAt: Column<Instant> = timestamp("created_at")
    public val lastLoginAt: Column<Instant?> = timestamp("last_login_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private const val EMAIL_LEN = 256
    private const val HASH_LEN = 256
    private const val DISPLAY_NAME_LEN = 128
    private const val ROLE_LEN = 16
}

/**
 * Granular permissions catalogue. Every row is a `resource:verb` token (e.g. `screens:create`,
 * `audit:export`). Preseeded by [dev.sdui.kmp.studio.server.rbac.RbacBootstrap]; the seed is
 * idempotent — running twice leaves the same rows in place.
 */
public object Permissions : Table("permissions") {
    public val id: Column<String> = varchar("id", PERMISSION_ID_LEN)
    public val description: Column<String> = varchar("description", DESCRIPTION_LEN)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private const val PERMISSION_ID_LEN = 64
    private const val DESCRIPTION_LEN = 256
}

/**
 * Roles. The three system roles `admin`, `editor`, `viewer` are preseeded with `is_system = true`
 * and cannot be deleted via the admin API. Operators may create custom roles freely.
 */
public object Roles : Table("roles") {
    public val id: Column<String> = varchar("id", ROLE_ID_LEN)
    public val name: Column<String> = varchar("name", NAME_LEN)
    public val description: Column<String> = varchar("description", DESCRIPTION_LEN)
    public val isSystem: Column<Boolean> = bool("is_system")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private const val ROLE_ID_LEN = 64
    private const val NAME_LEN = 128
    private const val DESCRIPTION_LEN = 256
}

/**
 * Many-to-many between [Roles] and [Permissions]. Composite primary key collapses duplicate
 * inserts cleanly; idempotent re-seed of system roles relies on this.
 */
public object RolePermissions : Table("role_permissions") {
    public val roleId: Column<String> = varchar("role_id", ROLE_ID_LEN)
    public val permissionId: Column<String> = varchar("permission_id", PERMISSION_ID_LEN)

    override val primaryKey: PrimaryKey = PrimaryKey(roleId, permissionId)

    private const val ROLE_ID_LEN = 64
    private const val PERMISSION_ID_LEN = 64
}

/**
 * Many-to-many between [EditorAccounts] and [Roles]. An editor's effective permissions are the
 * union of every linked role's [RolePermissions] rows. The legacy single-string
 * [EditorAccounts.role] column is migrated into this table by
 * [dev.sdui.kmp.studio.server.rbac.RbacBootstrap.migrateLegacyRoles].
 */
public object EditorRoles : Table("editor_roles") {
    public val editorId: Column<UUID> = uuid("editor_id")
    public val roleId: Column<String> = varchar("role_id", ROLE_ID_LEN)

    override val primaryKey: PrimaryKey = PrimaryKey(editorId, roleId)

    private const val ROLE_ID_LEN = 64
}

/**
 * One row per logical screen. Points at the currently-published version, or null while a
 * screen has been created but never published. `deleted_at` is a soft-delete tombstone — a
 * deleted screen keeps its history but disappears from the listing API.
 */
public object ScreenDefinitions : Table("screen_definitions") {
    public val screenId: Column<String> = varchar("screen_id", SCREEN_ID_LEN)
    public val currentVersionId: Column<UUID?> = uuid("current_version_id").nullable()
    public val createdAt: Column<Instant> = timestamp("created_at")
    public val updatedAt: Column<Instant> = timestamp("updated_at")
    public val deletedAt: Column<Instant?> = timestamp("deleted_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(screenId)

    private const val SCREEN_ID_LEN = 128
}

/**
 * Append-only history. A new row is inserted on every publish (and on every revert, which
 * copies a historical body forward to a new top version). Rows are never updated post-creation
 * apart from `published_at`, which is set at publish time.
 */
public object ScreenVersions : Table("screen_versions") {
    public val id: Column<UUID> = uuid("id")
    public val screenId: Column<String> = varchar("screen_id", SCREEN_ID_LEN).index()
    public val versionNumber: Column<Int> = integer("version_number")
    public val bodyJson: Column<String> = text("body_json")
    public val editorId: Column<UUID> = uuid("editor_id")
    public val createdAt: Column<Instant> = timestamp("created_at")
    public val publishedAt: Column<Instant?> = timestamp("published_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    init {
        uniqueIndex("screen_versions_screen_version_uq", screenId, versionNumber)
    }

    private const val SCREEN_ID_LEN = 128
}

/**
 * Mutable per-(screen, editor) drafts. At most one row per pair — the unique index enforces
 * that. A draft is destroyed when its owner publishes it (the body becomes a [ScreenVersions]
 * row instead). Drafts are private: editor A cannot see editor B's draft.
 */
public object ScreenDrafts : Table("screen_drafts") {
    public val id: Column<UUID> = uuid("id")
    public val screenId: Column<String> = varchar("screen_id", SCREEN_ID_LEN).index()
    public val bodyJson: Column<String> = text("body_json")
    public val editorId: Column<UUID> = uuid("editor_id")
    public val createdAt: Column<Instant> = timestamp("created_at")
    public val updatedAt: Column<Instant> = timestamp("updated_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    init {
        uniqueIndex("screen_drafts_screen_editor_uq", screenId, editorId)
    }

    private const val SCREEN_ID_LEN = 128
}

/**
 * Immutable audit trail. Every mutation (create, draft, publish, revert, delete) appends a
 * row. Compatible with the standard four-question audit answer: who did what to which screen
 * when, with which request id for cross-log correlation.
 *
 * M-S7 added `actor_ip` and `user_agent`. Both are nullable so historical rows remain valid
 * (they were inserted before the columns existed) and so test/CLI invocations that lack a
 * `call.request` context can still append.
 */
public object ScreenAuditLog : Table("screen_audit_log") {
    public val id: Column<UUID> = uuid("id")
    public val screenId: Column<String> = varchar("screen_id", SCREEN_ID_LEN).index()
    public val editorId: Column<UUID> = uuid("editor_id").index()
    public val action: Column<String> = varchar("action", ACTION_LEN)
    public val fromVersion: Column<Int?> = integer("from_version").nullable()
    public val toVersion: Column<Int?> = integer("to_version").nullable()
    public val at: Column<Instant> = timestamp("at").index()
    public val requestId: Column<String> = varchar("request_id", REQUEST_ID_LEN)
    public val actorIp: Column<String?> = varchar("actor_ip", ACTOR_IP_LEN).nullable()
    public val userAgent: Column<String?> = varchar("user_agent", USER_AGENT_LEN).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private const val SCREEN_ID_LEN = 128
    private const val ACTION_LEN = 32
    private const val REQUEST_ID_LEN = 64
    private const val ACTOR_IP_LEN = 64
    private const val USER_AGENT_LEN = 512
}

/**
 * Issued JWT sessions for Studio editors. Mirrors the sample-server's `Sessions` table — a row
 * here lets us revoke a still-valid token (e.g. on logout) before its `exp` claim lapses.
 */
public object EditorSessions : Table("editor_sessions") {
    public val id: Column<UUID> = uuid("id")
    public val editorId: Column<UUID> = uuid("editor_id").index()
    public val issuedAt: Column<Instant> = timestamp("issued_at")
    public val expiresAt: Column<Instant> = timestamp("expires_at")
    public val revokedAt: Column<Instant?> = timestamp("revoked_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * One screen-level A/B experiment. Lifecycle is `draft → active → paused → completed`; only
 * `active` experiments are eligible for client assignment by [dev.sdui.kmp.studio.server.experiments.AssignmentService].
 *
 * `screen_id` is the FK back to [ScreenDefinitions]. We DON'T enforce it as a SQL FK because the
 * existing `screen_definitions.screen_id` is a string PK — Exposed's `references` works on it but
 * adds an opaque cycle when two tables both `varchar` the same key. Application code keeps the
 * invariant: the route layer rejects experiments whose screen does not exist.
 */
public object Experiments : Table("experiments") {
    public val id: Column<String> = varchar("id", EXPERIMENT_ID_LEN)
    public val screenId: Column<String> = varchar("screen_id", SCREEN_ID_LEN).index()
    public val name: Column<String> = varchar("name", NAME_LEN)
    public val description: Column<String?> = text("description").nullable()
    public val status: Column<String> = varchar("status", STATUS_LEN)
    public val createdAt: Column<Instant> = timestamp("created_at")
    public val updatedAt: Column<Instant> = timestamp("updated_at")
    public val createdBy: Column<UUID> = uuid("created_by")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private const val EXPERIMENT_ID_LEN = 128
    private const val SCREEN_ID_LEN = 128
    private const val NAME_LEN = 128
    private const val STATUS_LEN = 16
}

/**
 * One row per variant within an experiment. Each variant carries a numeric `weight` (0..100),
 * and the sum of weights across an experiment must equal 100 — enforced at the route boundary
 * since we want a single 400 response, not per-row commits.
 *
 * `screen_version_id` points to the [ScreenVersions] row whose `body_json` is served when this
 * variant is selected. Editing the variant after publish does NOT mutate prior assignments —
 * sticky semantics are documented in `docs/adr/0018-studio-ab-targeting-model.md`.
 */
public object ExperimentVariants : Table("experiment_variants") {
    public val id: Column<String> = varchar("id", VARIANT_ID_LEN)
    public val experimentId: Column<String> = varchar("experiment_id", EXPERIMENT_ID_LEN).index()
    public val name: Column<String> = varchar("name", NAME_LEN)
    public val weight: Column<Int> = integer("weight")
    public val screenVersionId: Column<UUID> = uuid("screen_version_id")
    public val createdAt: Column<Instant> = timestamp("created_at")
    public val createdBy: Column<UUID> = uuid("created_by")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private const val VARIANT_ID_LEN = 128
    private const val EXPERIMENT_ID_LEN = 128
    private const val NAME_LEN = 64
}

/**
 * Reusable audience definition. `predicate_json` is the kotlinx-serialization-encoded
 * [dev.sdui.kmp.studio.server.experiments.AudiencePredicate] tree. Multiple experiments may
 * reference the same audience via [ExperimentAudiences]; an experiment is eligible for a client
 * only if EVERY linked audience evaluates to true (AND across rows).
 */
public object Audiences : Table("audiences") {
    public val id: Column<String> = varchar("id", AUDIENCE_ID_LEN)
    public val name: Column<String> = varchar("name", NAME_LEN)
    public val description: Column<String?> = text("description").nullable()
    public val predicateJson: Column<String> = text("predicate_json")
    public val createdAt: Column<Instant> = timestamp("created_at")
    public val createdBy: Column<UUID> = uuid("created_by")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    private const val AUDIENCE_ID_LEN = 128
    private const val NAME_LEN = 128
}

/**
 * Many-to-many join between [Experiments] and [Audiences]. Composite primary key — duplicate
 * inserts collapse cleanly. AND-of-audiences semantics: a client must match every linked
 * audience to be eligible for the experiment.
 */
public object ExperimentAudiences : Table("experiment_audiences") {
    public val experimentId: Column<String> = varchar("experiment_id", EXPERIMENT_ID_LEN)
    public val audienceId: Column<String> = varchar("audience_id", AUDIENCE_ID_LEN)

    override val primaryKey: PrimaryKey = PrimaryKey(experimentId, audienceId)

    private const val EXPERIMENT_ID_LEN = 128
    private const val AUDIENCE_ID_LEN = 128
}

/**
 * Sticky variant assignment per `(experimentId, clientId)`. Once a row exists,
 * [dev.sdui.kmp.studio.server.experiments.AssignmentService] reuses it on every subsequent
 * `assign()` call so weights changing mid-experiment never flips a client between variants.
 *
 * `client_id` is whatever stable identifier the calling server passes — typically a hashed
 * user-id, an installation UUID, or the bearer-token subject. We never require it to be a UUID
 * because federated clients may use opaque strings.
 */
public object ExperimentAssignments : Table("experiment_assignments") {
    public val experimentId: Column<String> = varchar("experiment_id", EXPERIMENT_ID_LEN)
    public val clientId: Column<String> = varchar("client_id", CLIENT_ID_LEN)
    public val variantId: Column<String> = varchar("variant_id", VARIANT_ID_LEN)
    public val assignedAt: Column<Instant> = timestamp("assigned_at")

    override val primaryKey: PrimaryKey = PrimaryKey(experimentId, clientId)

    private const val EXPERIMENT_ID_LEN = 128
    private const val CLIENT_ID_LEN = 256
    private const val VARIANT_ID_LEN = 128
}

/**
 * The full set of Studio tables, in the order the runtime H2 auto-DDL path creates them.
 *
 * Single source of truth shared by [StudioDatabase.connect] (which spreads it into
 * `SchemaUtils.createMissingTablesAndColumns` on the H2 dev/test path) and the migration parity
 * test, which asserts the Flyway migration set under `db/migrations/` covers every table and
 * column listed here. Add a new table in exactly one place — here — and both the H2 fallback and
 * the migration-coverage check pick it up.
 */
internal val StudioTables: List<Table> = listOf(
    EditorAccounts,
    ScreenDefinitions,
    ScreenVersions,
    ScreenDrafts,
    ScreenAuditLog,
    EditorSessions,
    Experiments,
    ExperimentVariants,
    Audiences,
    ExperimentAudiences,
    ExperimentAssignments,
    // M-S7: granular RBAC.
    Permissions,
    Roles,
    RolePermissions,
    EditorRoles,
)
