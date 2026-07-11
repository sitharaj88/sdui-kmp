# sample-server

Reference Ktor server demonstrating `sdui-kmp` server-driven UI emission, JWT auth,
idempotency-key replay, structured logging, and database-backed session tracking.

## Run modes

### 1. Local fast path — `./gradlew :samples:sample-server:run`

No Postgres, no Docker. The server starts on `localhost:8080` and uses an in-memory H2
database for sessions / idempotency / conflicts. A warning line is printed at startup so
you cannot accidentally take this mode to production:

```
[sample-server] JDBC_URL not set — using in-memory H2 database. ...
```

Data is wiped on every restart. Suitable for protocol exploration and the demo clients.

### 2. Production-shaped — `docker compose up`

From this directory:

```bash
docker compose up --build
```

Starts three services:

- `db`     — Postgres 16, port 5432 exposed for psql poking.
- `flyway` — one-shot migration runner that applies `db/migrations/V1__initial.sql`.
- `app`    — the sample server, listening on `localhost:8080`.

Tear down with `docker compose down` (preserves data) or `docker compose down -v` (wipes
the Postgres volume).

## Endpoints

| Method | Path              | Auth     | Notes                                         |
|-------:|-------------------|----------|-----------------------------------------------|
| GET    | `/health`         | none     | Liveness — always 200.                        |
| GET    | `/readiness`      | none     | 200 iff DB probe + JWT key OK; else 503 JSON. |
| POST   | `/auth/login`     | none     | Returns `{"token": "..."}`. Demo password is `password`. |
| GET    | `/screens/...`    | JWT      | SDUI screen payloads.                         |
| WS     | `/live/ticker`    | none     | Demo live-state stream.                       |

## Idempotency

Any `POST` / `PUT` / `PATCH` request carrying an `X-Idempotency-Key` header is replayed
from the cache when a previous successful response exists for the same `(key, subject,
endpoint)` triple. Replays carry an `X-Idempotency-Replayed: true` response header.
Default TTL is 24 hours; entries are purged lazily on the next write to the same key.

## Request correlation

Every request is tagged with an `X-Request-Id`. If the inbound request supplies one we
honor it; otherwise the server generates a UUID. The id is placed in SLF4J MDC under the
`request_id` key, so each JSON log line includes it under `mdc.request_id`.

## Structured logging

All logs are emitted as single-line JSON via `logback-jackson`. Sample line:

```json
{"timestamp":"2026-04-25T10:00:00.123Z","level":"INFO","logger":"access","thread":"DefaultDispatcher-worker-1","message":"POST /auth/login 200 12ms","mdc":{"request_id":"a1b2c3..."}}
```

Adjust verbosity via `src/main/resources/logback.xml`.

## Environment variables

| Var               | Default     | Notes                                              |
|-------------------|-------------|----------------------------------------------------|
| `PORT`            | `8080`      | TCP port to bind.                                  |
| `JDBC_URL`        | unset       | If unset → H2 fallback. If set, must be a JDBC URL.|
| `JDBC_USER`       | `sdui`      | Postgres user when `JDBC_URL` is set.              |
| `JDBC_PASSWORD`   | `""`        | Postgres password when `JDBC_URL` is set.          |
| `JDBC_MAX_POOL_SIZE` | `10`     | HikariCP maximum-pool-size override.               |
| `JAVA_OPTS`       | tuned       | Set in the Dockerfile; override in production.     |

## Tests

```bash
./gradlew :samples:sample-server:test --no-daemon
```

Covers:

- `AuthTest`: login, JWT round-trip, protected screens.
- `ScreensTest`: protocol round-trip on every screen.
- `IdempotencyTest`: replay-on-second-call vs distinct-key.
- `ReadinessTest`: 200 with DB up, 503 with broken DataSource.

## Schema source of truth

The Exposed table definitions in `src/main/kotlin/.../db/Schema.kt` and the Flyway
migration in `db/migrations/V1__initial.sql` describe the same schema. Keep them in
lockstep when adding columns — the Exposed call at startup applies missing columns
idempotently for the H2 fallback, but production deployments depend on Flyway only.
