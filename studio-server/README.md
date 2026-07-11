# `:studio-server` — Studio backend (admin REST + Postgres)

JVM-only Ktor service that lets editors author, version, and publish screens through an admin
REST API. Runs side-by-side with `:samples:sample-server`: studio on port **8081**, sample on
**8080**.

Status: M-S2 (data model + REST surface). The WebSocket fan-out on publish is forward-declared
via `PublishNotifier` but **not yet wired** — that lands in S3.

## Run locally

```bash
./gradlew :studio-server:run
```

With no environment configuration the server boots against an in-memory **H2** database and
serves at `http://localhost:8081`. Editor accounts must be inserted directly in the DB until a
user-management route lands; for tests see `EditorAccountStore.create(...)`.

## Configuration

Every value is read from an environment variable:

| Var                  | Default                      | Notes                                                        |
| -------------------- | ---------------------------- | ------------------------------------------------------------ |
| `PORT`               | `8081`                       | Studio HTTP port.                                            |
| `HOST`               | `0.0.0.0`                    | Bind address.                                                |
| `JDBC_URL`           | _(unset → in-memory H2)_     | Postgres JDBC URL, e.g. `jdbc:postgresql://db:5432/studio`.  |
| `JDBC_USER`          | `sdui_studio`                | DB user.                                                     |
| `JDBC_PASSWORD`      | _(empty)_                    | DB password.                                                 |
| `JDBC_MAX_POOL_SIZE` | `10`                         | Hikari `maximumPoolSize` for Postgres.                       |
| `STUDIO_JWT_SECRET`  | _(fallback sample-only key)_ | HMAC256 signing secret. Required in production.              |

## Routes

All routes mount under `/admin/`. Bearer JWT required on every route except `POST
/admin/auth/login`. Roles: `admin` > `editor` > `viewer` (transitive).

| Method | Path                                | Min role | Notes                                              |
| ------ | ----------------------------------- | -------- | -------------------------------------------------- |
| `POST` | `/admin/auth/login`                 | _public_ | Body `{email, password}` → `{token, expiresAt, role}`. |
| `POST` | `/admin/auth/logout`                | viewer   | Revokes the bearer token's session row.            |
| `GET`  | `/admin/screens`                    | viewer   | Listing with `hasDraft` flag for the caller.       |
| `GET`  | `/admin/screens/{id}`               | viewer   | Currently-published version of a screen.           |
| `GET`  | `/admin/screens/{id}/draft`         | editor   | Calling editor's draft (404 if none).              |
| `PUT`  | `/admin/screens/{id}/draft`         | editor   | Body is full Screen JSON. Validates structurally.  |
| `POST` | `/admin/screens/{id}/publish`       | editor   | Promotes draft → new top version.                  |
| `GET`  | `/admin/screens/{id}/versions`      | viewer   | Paginated history; newest first.                   |
| `POST` | `/admin/screens/{id}/revert?to=N`   | admin    | Copies version N into a new top version.           |
| `DELETE` | `/admin/screens/{id}`             | admin    | Soft-delete; history preserved.                    |
| `GET`  | `/admin/audit`                      | viewer   | Filters: `screenId`, `editorId`, `from`, `to`.     |

## Data model

Fifteen tables, mirrored in Exposed (`db/StudioSchema.kt`, single source of truth `StudioTables`)
and the Flyway migration set under `db/migrations/` (`V1__studio_initial.sql`,
`V2__experiments.sql`, `V3__rbac_and_audit_context.sql`).

Core screen model:

- `editor_accounts` — bcrypt-hashed credentials + legacy role.
- `screen_definitions` — one row per logical screen, points at the current published version.
- `screen_versions` — append-only history per screen; unique on `(screen_id, version_number)`.
- `screen_drafts` — at most one draft per `(screen_id, editor_id)`.
- `screen_audit_log` — immutable trail keyed on `(screen_id, editor_id, at, request_id)`.
- `editor_sessions` — issued JWTs, with a `revoked_at` column for logout.

A/B experiments (`V2`): `experiments`, `experiment_variants`, `audiences`,
`experiment_audiences`, `experiment_assignments`.

Granular RBAC (`V3`): `permissions`, `roles`, `role_permissions`, `editor_roles`.

### Schema management

Production Postgres is provisioned **only** by Flyway (apply `db/migrations/V*.sql` out-of-process
before boot — same one-shot `flyway` container the sample-server uses). `StudioDatabase.connect()`
runs Exposed `SchemaUtils` auto-DDL **only** on the H2 dev/test fallback, never against Postgres,
so production never depends on unordered runtime `ALTER`s. `MigrationSchemaParityTest` migrates the
whole `V*.sql` set against PostgreSQL-mode H2 and asserts it covers every `StudioTables` table and
column, so the SQL and Exposed schema cannot silently drift.

## Validation

`PUT /admin/screens/{id}/draft` runs every body through `Screen.serializer()` first. Malformed
JSON or any structural mismatch (unknown discriminator, wrong field type, missing required
field) returns `400 Bad Request` with the violation list. The committed `protocol-snapshot.json`
is bundled into the classpath at build time and the studio cross-checks the runtime protocol
against it on startup — drift logs a warning.

## Deferred / non-goals

- **Live publish hook.** `PublishNotifier` is forward-declared as a `fun interface` and called
  from the publish + revert routes after a successful commit. The default is a no-op; S3 will
  inject a `WebSocketLivePublisher` reusing `:transport-live`.
- **Account self-service.** No `POST /admin/accounts` route yet — accounts are inserted via
  `EditorAccountStore.create` from a setup script. Owned by S4.
- **Rate limiting.** Owned by the parallel auth-hardening agent (`:auth-rs256`'s plugin set);
  studio routes will pick that up when the plugin is published.
- **RS256.** `StudioJwt` uses HMAC256 today; once `:auth-rs256`'s `Rs256JwtIssuer` lands the
  studio will swap algorithms — the JWT claim shape was chosen up front to make it a one-line
  edit.
