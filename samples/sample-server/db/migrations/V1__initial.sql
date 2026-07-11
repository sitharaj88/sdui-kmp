-- V1: Initial schema for the sample server.
-- Mirrors the Exposed table definitions in
--   samples/sample-server/src/main/kotlin/dev/sdui/kmp/sample/server/db/Schema.kt
-- Keep both in lockstep when adding fields.

CREATE TABLE IF NOT EXISTS sessions (
    id          UUID         PRIMARY KEY,
    subject     TEXT         NOT NULL,
    issued_at   TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    revoked_at  TIMESTAMP    NULL
);

CREATE INDEX IF NOT EXISTS sessions_subject_idx ON sessions (subject);
CREATE INDEX IF NOT EXISTS sessions_expires_at_idx ON sessions (expires_at);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key            TEXT       NOT NULL,
    subject        TEXT       NOT NULL,
    endpoint       TEXT       NOT NULL,
    response_body  TEXT       NOT NULL,
    created_at     TIMESTAMP  NOT NULL,
    expires_at     TIMESTAMP  NOT NULL,
    PRIMARY KEY (key, subject, endpoint)
);

CREATE INDEX IF NOT EXISTS idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);

CREATE TABLE IF NOT EXISTS optimistic_conflicts (
    key              TEXT       PRIMARY KEY,
    submitted_state  TEXT       NOT NULL,
    current_state    TEXT       NOT NULL,
    created_at       TIMESTAMP  NOT NULL,
    resolved_at      TIMESTAMP  NULL
);

CREATE INDEX IF NOT EXISTS optimistic_conflicts_resolved_at_idx ON optimistic_conflicts (resolved_at);
