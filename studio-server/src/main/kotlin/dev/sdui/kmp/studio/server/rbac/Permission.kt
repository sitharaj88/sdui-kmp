package dev.sdui.kmp.studio.server.rbac

/**
 * Canonical catalogue of permission tokens recognised by the Studio backend. Every token
 * follows the `resource:verb` convention so it lines up cleanly with route paths
 * (`/admin/screens` → `screens:*`, `/admin/audit` → `audit:*`).
 *
 * The list is stable — once a token ships in this catalogue it must not be renamed or removed
 * (mirrors the protocol's additive-only red line from `VISION.md`). Adding a new permission is
 * fine; rename means a new token plus a deprecation window for the old one.
 *
 * Tokens are inserted into the [dev.sdui.kmp.studio.server.db.Permissions] table by
 * [RbacBootstrap.bootstrap], which iterates [All]. The `description` is what appears in the
 * `/admin/roles` editor UI when an operator picks permissions to assign.
 */
public object Permission {
    // Screens.
    public const val SCREENS_READ: String = "screens:read"
    public const val SCREENS_CREATE: String = "screens:create"
    public const val SCREENS_UPDATE: String = "screens:update"
    public const val SCREENS_DELETE: String = "screens:delete"

    // Drafts (per-(screen, editor) editing surface).
    public const val DRAFTS_READ: String = "drafts:read"
    public const val DRAFTS_CREATE: String = "drafts:create"
    public const val DRAFTS_UPDATE: String = "drafts:update"
    public const val DRAFTS_DELETE: String = "drafts:delete"

    // Versions: revert + publish are split because publish is the more common path and we
    // want to allow editors to publish without granting them revert (which can rewrite the
    // production tree to an arbitrary historical body).
    public const val VERSIONS_PUBLISH: String = "versions:publish"
    public const val VERSIONS_REVERT: String = "versions:revert"

    // Experiments + audiences.
    public const val EXPERIMENTS_READ: String = "experiments:read"
    public const val EXPERIMENTS_CREATE: String = "experiments:create"
    public const val EXPERIMENTS_UPDATE: String = "experiments:update"
    public const val EXPERIMENTS_DELETE: String = "experiments:delete"
    public const val EXPERIMENTS_PUBLISH: String = "experiments:publish"

    public const val AUDIENCES_READ: String = "audiences:read"
    public const val AUDIENCES_CREATE: String = "audiences:create"
    public const val AUDIENCES_UPDATE: String = "audiences:update"
    public const val AUDIENCES_DELETE: String = "audiences:delete"

    // Audit log.
    public const val AUDIT_READ: String = "audit:read"
    public const val AUDIT_EXPORT: String = "audit:export"

    // Editor account administration.
    public const val EDITORS_READ: String = "editors:read"
    public const val EDITORS_CREATE: String = "editors:create"
    public const val EDITORS_UPDATE: String = "editors:update"
    public const val EDITORS_DELETE: String = "editors:delete"

    // Role administration.
    public const val ROLES_READ: String = "roles:read"
    public const val ROLES_CREATE: String = "roles:create"
    public const val ROLES_UPDATE: String = "roles:update"
    public const val ROLES_DELETE: String = "roles:delete"

    /** Description rendered next to each permission in the role-editor UI. */
    public val Descriptions: Map<String, String> = linkedMapOf(
        SCREENS_READ to "List and read published screens",
        SCREENS_CREATE to "Create new screen definitions",
        SCREENS_UPDATE to "Update screen metadata",
        SCREENS_DELETE to "Soft-delete screens",
        DRAFTS_READ to "Read your own drafts",
        DRAFTS_CREATE to "Create drafts",
        DRAFTS_UPDATE to "Save drafts",
        DRAFTS_DELETE to "Discard drafts",
        VERSIONS_PUBLISH to "Promote a draft to a published version",
        VERSIONS_REVERT to "Revert a screen to a prior published version",
        EXPERIMENTS_READ to "List and read experiments",
        EXPERIMENTS_CREATE to "Create experiments and variants",
        EXPERIMENTS_UPDATE to "Update experiment status and weights",
        EXPERIMENTS_DELETE to "Delete experiments",
        EXPERIMENTS_PUBLISH to "Promote a winning variant to the published version",
        AUDIENCES_READ to "List and read audiences",
        AUDIENCES_CREATE to "Create audiences",
        AUDIENCES_UPDATE to "Update audience predicates",
        AUDIENCES_DELETE to "Delete audiences",
        AUDIT_READ to "Read the audit log",
        AUDIT_EXPORT to "Export the audit log to CSV/JSON/JSONL",
        EDITORS_READ to "List editor accounts",
        EDITORS_CREATE to "Create editor accounts",
        EDITORS_UPDATE to "Update editor accounts and role assignments",
        EDITORS_DELETE to "Delete editor accounts",
        ROLES_READ to "List custom and system roles",
        ROLES_CREATE to "Create custom roles",
        ROLES_UPDATE to "Update permissions assigned to a role",
        ROLES_DELETE to "Delete custom roles (system roles cannot be deleted)",
    )

    /** Every defined permission token, in stable insertion order. */
    public val All: List<String> get() = Descriptions.keys.toList()
}

/**
 * Identifiers and preset permission sets for the three preseeded system roles. Custom roles
 * created through the admin API live alongside these but never collide on id (the route layer
 * rejects creates with [SystemRoleIds]).
 */
public object SystemRoles {
    public const val ADMIN: String = "admin"
    public const val EDITOR: String = "editor"
    public const val VIEWER: String = "viewer"

    /** Set of system role ids used by the route layer to reject tampering attempts. */
    public val SystemRoleIds: Set<String> = setOf(ADMIN, EDITOR, VIEWER)

    /** `admin` gets every permission the catalogue defines. */
    public val AdminPermissions: List<String> = Permission.All

    /**
     * `editor` gets every screen / draft / experiment / audience permission plus
     * `versions:publish` and `audit:read`. They cannot revert prior versions, manage editor
     * accounts, role definitions, or export audit — those stay admin-only to preserve the
     * pre-S7 behavior captured by `RevertTest.editor_cannot_revert`.
     */
    public val EditorPermissions: List<String> = listOf(
        Permission.SCREENS_READ,
        Permission.SCREENS_CREATE,
        Permission.SCREENS_UPDATE,
        Permission.SCREENS_DELETE,
        Permission.DRAFTS_READ,
        Permission.DRAFTS_CREATE,
        Permission.DRAFTS_UPDATE,
        Permission.DRAFTS_DELETE,
        Permission.VERSIONS_PUBLISH,
        Permission.EXPERIMENTS_READ,
        Permission.EXPERIMENTS_CREATE,
        Permission.EXPERIMENTS_UPDATE,
        Permission.EXPERIMENTS_DELETE,
        Permission.EXPERIMENTS_PUBLISH,
        Permission.AUDIENCES_READ,
        Permission.AUDIENCES_CREATE,
        Permission.AUDIENCES_UPDATE,
        Permission.AUDIENCES_DELETE,
        Permission.AUDIT_READ,
    )

    /** `viewer` gets every `*:read` permission — listings only, no mutations. */
    public val ViewerPermissions: List<String> = Permission.All.filter { it.endsWith(":read") }

    /** Map a system role id to its preset permission set. */
    public fun permissionsFor(roleId: String): List<String> = when (roleId) {
        ADMIN -> AdminPermissions
        EDITOR -> EditorPermissions
        VIEWER -> ViewerPermissions
        else -> emptyList()
    }
}
