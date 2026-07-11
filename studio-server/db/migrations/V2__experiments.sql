-- V2: A/B experiment surface for the Studio backend.
-- Mirrors the Exposed table definitions in
--   studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/StudioSchema.kt
--   (Experiments, ExperimentVariants, Audiences, ExperimentAudiences, ExperimentAssignments)
-- Additive-only: this migration adds new tables, it never alters or drops V1 objects.
-- Keep the SQL and the Exposed schema in lockstep (StudioMigrationSchemaTest enforces coverage).

-- One screen-level A/B experiment. Lifecycle: draft -> active -> paused -> completed.
CREATE TABLE IF NOT EXISTS experiments (
    id           VARCHAR(128) PRIMARY KEY,
    screen_id    VARCHAR(128) NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  TEXT         NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    created_by   UUID         NOT NULL
);

CREATE INDEX IF NOT EXISTS experiments_screen_id_idx ON experiments (screen_id);

-- One row per variant within an experiment. `weight` is 0..100; the per-experiment sum must
-- equal 100 (enforced at the route boundary). `screen_version_id` points at the published body.
CREATE TABLE IF NOT EXISTS experiment_variants (
    id                 VARCHAR(128) PRIMARY KEY,
    experiment_id      VARCHAR(128) NOT NULL,
    name               VARCHAR(64)  NOT NULL,
    weight             INT          NOT NULL,
    screen_version_id  UUID         NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    created_by         UUID         NOT NULL
);

CREATE INDEX IF NOT EXISTS experiment_variants_experiment_id_idx ON experiment_variants (experiment_id);

-- Reusable audience definition. `predicate_json` is the serialized AudiencePredicate tree.
CREATE TABLE IF NOT EXISTS audiences (
    id              VARCHAR(128) PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     TEXT         NULL,
    predicate_json  TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    created_by      UUID         NOT NULL
);

-- Many-to-many join between experiments and audiences (AND-of-audiences semantics).
CREATE TABLE IF NOT EXISTS experiment_audiences (
    experiment_id  VARCHAR(128) NOT NULL,
    audience_id    VARCHAR(128) NOT NULL,
    CONSTRAINT experiment_audiences_pk PRIMARY KEY (experiment_id, audience_id)
);

-- Sticky per-(experiment, client) variant assignment. Once written, reused on every assign().
CREATE TABLE IF NOT EXISTS experiment_assignments (
    experiment_id  VARCHAR(128) NOT NULL,
    client_id      VARCHAR(256) NOT NULL,
    variant_id     VARCHAR(128) NOT NULL,
    assigned_at    TIMESTAMP    NOT NULL,
    CONSTRAINT experiment_assignments_pk PRIMARY KEY (experiment_id, client_id)
);
