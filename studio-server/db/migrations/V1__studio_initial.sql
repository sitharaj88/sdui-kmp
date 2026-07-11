-- V1: Initial schema for the Studio backend.
-- Mirrors the Exposed table definitions in
--   studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/StudioSchema.kt
-- Keep both in lockstep when adding fields.

-- Editor accounts: who can sign in to the Studio.
CREATE TABLE IF NOT EXISTS editor_accounts (
    id              UUID         PRIMARY KEY,
    email           VARCHAR(256) NOT NULL UNIQUE,
    password_hash   VARCHAR(256) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    last_login_at   TIMESTAMP    NULL
);

CREATE INDEX IF NOT EXISTS editor_accounts_email_idx ON editor_accounts (email);

-- One row per published screen — points at the currently-published version.
CREATE TABLE IF NOT EXISTS screen_definitions (
    screen_id            VARCHAR(128) PRIMARY KEY,
    current_version_id   UUID         NULL,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    deleted_at           TIMESTAMP    NULL
);

-- Append-only history of published versions.
CREATE TABLE IF NOT EXISTS screen_versions (
    id              UUID         PRIMARY KEY,
    screen_id       VARCHAR(128) NOT NULL,
    version_number  INT          NOT NULL,
    body_json       TEXT         NOT NULL,
    editor_id       UUID         NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    published_at    TIMESTAMP    NULL,
    CONSTRAINT screen_versions_screen_version_uq UNIQUE (screen_id, version_number)
);

CREATE INDEX IF NOT EXISTS screen_versions_screen_id_idx ON screen_versions (screen_id);

-- Mutable per-(screen, editor) drafts. At most one draft row per pair.
CREATE TABLE IF NOT EXISTS screen_drafts (
    id          UUID         PRIMARY KEY,
    screen_id   VARCHAR(128) NOT NULL,
    body_json   TEXT         NOT NULL,
    editor_id   UUID         NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT screen_drafts_screen_editor_uq UNIQUE (screen_id, editor_id)
);

CREATE INDEX IF NOT EXISTS screen_drafts_screen_id_idx ON screen_drafts (screen_id);

-- Immutable audit trail of every Studio mutation.
CREATE TABLE IF NOT EXISTS screen_audit_log (
    id            UUID         PRIMARY KEY,
    screen_id     VARCHAR(128) NOT NULL,
    editor_id     UUID         NOT NULL,
    action        VARCHAR(32)  NOT NULL,
    from_version  INT          NULL,
    to_version    INT          NULL,
    at            TIMESTAMP    NOT NULL,
    request_id    VARCHAR(64)  NOT NULL
);

CREATE INDEX IF NOT EXISTS screen_audit_log_screen_id_idx ON screen_audit_log (screen_id);
CREATE INDEX IF NOT EXISTS screen_audit_log_editor_id_idx ON screen_audit_log (editor_id);
CREATE INDEX IF NOT EXISTS screen_audit_log_at_idx ON screen_audit_log (at);

-- Tracks issued JWTs so an editor can be logged out before `exp` lapses.
CREATE TABLE IF NOT EXISTS editor_sessions (
    id           UUID      PRIMARY KEY,
    editor_id    UUID      NOT NULL,
    issued_at    TIMESTAMP NOT NULL,
    expires_at   TIMESTAMP NOT NULL,
    revoked_at   TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS editor_sessions_editor_id_idx ON editor_sessions (editor_id);
CREATE INDEX IF NOT EXISTS editor_sessions_expires_at_idx ON editor_sessions (expires_at);
