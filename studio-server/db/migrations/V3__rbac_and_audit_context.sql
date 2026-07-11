-- V3 (M-S7): granular RBAC tables + audit-context columns.
-- Mirrors the Exposed table definitions in
--   studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/StudioSchema.kt
--   (Permissions, Roles, RolePermissions, EditorRoles) plus the actor_ip / user_agent columns
--   added to screen_audit_log.
-- Additive-only: new tables + nullable columns; V1/V2 objects are never altered destructively.
-- Row seeding (system roles/permissions, legacy-role migration) is done idempotently at boot by
-- RbacBootstrap — this migration only provisions the schema those seeds land in.

-- Granular permission catalogue. Each id is a `resource:verb` token (e.g. `screens:create`).
CREATE TABLE IF NOT EXISTS permissions (
    id           VARCHAR(64)  PRIMARY KEY,
    description  VARCHAR(256) NOT NULL
);

-- Roles. System roles (admin/editor/viewer) are seeded with is_system = true.
CREATE TABLE IF NOT EXISTS roles (
    id           VARCHAR(64)  PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(256) NOT NULL,
    is_system    BOOLEAN      NOT NULL
);

-- Many-to-many between roles and permissions.
CREATE TABLE IF NOT EXISTS role_permissions (
    role_id        VARCHAR(64) NOT NULL,
    permission_id  VARCHAR(64) NOT NULL,
    CONSTRAINT role_permissions_pk PRIMARY KEY (role_id, permission_id)
);

-- Many-to-many between editor accounts and roles.
CREATE TABLE IF NOT EXISTS editor_roles (
    editor_id  UUID        NOT NULL,
    role_id    VARCHAR(64) NOT NULL,
    CONSTRAINT editor_roles_pk PRIMARY KEY (editor_id, role_id)
);

-- Audit context added in M-S7. Nullable so historical rows (inserted before the columns existed)
-- stay valid and test/CLI invocations without a request context can still append.
ALTER TABLE screen_audit_log ADD COLUMN IF NOT EXISTS actor_ip   VARCHAR(64)  NULL;
ALTER TABLE screen_audit_log ADD COLUMN IF NOT EXISTS user_agent VARCHAR(512) NULL;
