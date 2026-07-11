# Operational runbook — sdui-kmp servers

Audience: on-call engineer running the production sample-server (`/screens/*`, `/auth/*`,
`/live/screens/{id}`) and studio-server (`/admin/*`) deployments.

This is the operator-facing pendant to [docs/STUDIO.md](../STUDIO.md) (developer view of
the same backends). For incident response see [incident-response.md](incident-response.md);
for SLOs see [slos.md](slos.md).

---

## 1. Topology

Two JVM services, both built from this repo via `./gradlew :samples:sample-server:installDist`
and `./gradlew :studio-server:installDist`.

| Service | Default port | Owns |
|---------|--------------|------|
| `:samples:sample-server` | `8080` | Public-facing screen API (`GET /screens/{id}`), JWT login (`POST /auth/login`), live fan-out (`/live/screens/{id}`), JWKS (`/.well-known/jwks.json`) |
| `:studio-server`         | `8081` | Editor admin REST (`/admin/*`), publish notifier, audit log, A/B experiments (`/experiments`, `/audiences`) |

Both services share the **same `:protocol`** module on the classpath, so a screen authored
in studio is byte-identical to one served by sample-server. They do **not** share databases;
each has its own Postgres schema (see [`§3 Persistence`](#3-persistence)).

Live publish flow today is per-process — studio-server's `WebSocketLivePublisher` does NOT
fan out to sample-server. See `samples/sample-server/src/main/kotlin/.../Main.kt` `samplePublisher`
docs for the three options to bridge the gap (combined app, internal HTTP, Redis pub/sub);
none are wired in this repo.

---

## 2. Deployment

### Prebuilt artifacts

The release workflow (`.github/workflows/release.yml`) publishes both services as
distribution tarballs alongside the library JARs, plus the docker-compose stack that
boots Postgres + Flyway + the two services. Locally:

```bash
./gradlew :samples:sample-server:installDist
./gradlew :studio-server:installDist
# tarballs land at samples/sample-server/build/distributions/sample-server-<version>.tar
# and studio-server/build/distributions/studio-server-<version>.tar
```

### Run

Each tarball ships `bin/sample-server` (or `bin/studio-server`) as the entry script:

```bash
tar xf sample-server-1.0.0.tar
cd sample-server-1.0.0
JDBC_URL=jdbc:postgresql://pg:5432/sdui_sample \
JDBC_USER=sdui \
JDBC_PASSWORD=$(cat /run/secrets/sdui_pg_password) \
PORT=8080 \
RSA_PRIVATE_KEY="$(cat /run/secrets/jwt_private.pem)" \
RSA_PUBLIC_KEY="$(cat /run/secrets/jwt_public.pem)" \
RSA_KEY_ID=sdui-prod-2026Q2 \
SENTRY_DSN=$(cat /run/secrets/sentry_dsn) \
bin/sample-server
```

Studio-server reads the same `JDBC_*` triple and additionally:

```bash
JDBC_URL=jdbc:postgresql://pg:5432/sdui_studio \
JDBC_USER=sdui_studio \
STUDIO_JWT_SECRET=$(cat /run/secrets/studio_jwt_secret) \
STUDIO_BCRYPT_COST=12 \
PORT=8081 \
bin/studio-server
```

`STUDIO_JWT_SECRET` is loaded by `StudioJwt.fromEnv()` (HMAC256 today; the parallel
hardening agent will swap to RS256 with the same env-var contract `:auth-rs256` already uses).

---

## 3. Persistence

Both services connect via HikariCP / Exposed. **Production must set `JDBC_URL`**; without
it Db.kt boots an H2 in-memory fallback that wipes on restart and prints a one-line stderr
warning. The boot log is structured JSON, so the warning is searchable as
`logger=Db level=WARN msg="JDBC_URL not set"`.

| Variable | Default | Notes |
|----------|---------|-------|
| `JDBC_URL` | (none — H2 fallback) | `jdbc:postgresql://host:5432/db` |
| `JDBC_USER` | `sdui` (sample) / `sdui_studio` (studio) | Read-write user |
| `JDBC_PASSWORD` | (empty) | Mount via secret |
| `JDBC_MAX_POOL_SIZE` | 10 | HikariCP `maximumPoolSize` |

Schema is applied at boot via Exposed `SchemaUtils.createMissingTablesAndColumns`; the
production-shaped Docker compose stack also runs Flyway migrations from `db/migrations/`
to keep DDL identical between dev and prod. Tables created:

- sample-server: `sessions`, `idempotency_keys`, `optimistic_conflicts`.
- studio-server: `editor_accounts`, `editor_sessions`, `screens`, `screen_versions`,
  `screen_drafts`, `audit_log`, `experiments`, `audiences`, `audience_assignments`.

---

## 4. Health & readiness endpoints

| Path | Service(s) | What it does |
|------|------------|--------------|
| `GET /health`       | both | Always 200, body `{"status":"ok"}`. Liveness only — k8s `livenessProbe`. |
| `GET /readiness`    | sample-server only | DB probe (`SELECT 1`) + JWT signing key smoke check. 200 if healthy, 503 with `{"ready":false,"reasons":[...]}` otherwise. Use as k8s `readinessProbe`. |
| `GET /metrics`      | both | Prometheus text format. Scrape with the Grafana / Prometheus stack — see [grafana-dashboard.json](grafana-dashboard.json). |
| `GET /.well-known/jwks.json` | sample-server | Public JWKS document. Federated services verify tokens against this. |

Studio-server does NOT yet expose `/readiness` — the same DB probe pattern would be a
small, unblocking follow-up; until then operators run a smoke `GET /admin/screens/list`
with a known-good token to confirm DB connectivity end-to-end.

---

## 5. JWT key management

### sample-server (RS256)

`:auth-rs256` reads three env vars through [SecretsProvider](../../auth-rs256/src/main/kotlin/dev/sdui/kmp/auth/rs256/SecretsProvider.kt):

| Var | Format |
|-----|--------|
| `RSA_PRIVATE_KEY` | PEM PKCS#8 (`-----BEGIN PRIVATE KEY-----` ...) |
| `RSA_PUBLIC_KEY`  | PEM X.509 SubjectPublicKeyInfo |
| `RSA_KEY_ID`      | Opaque id; must match the JWKS `kid` field |

If unset, `Rs256JwtIssuer` generates an ephemeral pair at boot. **Tokens become
unverifiable on restart.** Always set these in production.

### Rotating sample-server keys

1. Generate the new pair locally:
   ```bash
   openssl genrsa -out private.pem 2048
   openssl rsa -in private.pem -pubout -out public.pem
   ```
2. Mint a new `RSA_KEY_ID`, e.g. `sdui-prod-2026Q3`.
3. Roll the secret in your secrets manager (Vault / AWS Secrets Manager / k8s Secret).
4. Restart sample-server pods rolling, one at a time. The JWKS document republishes
   the new public key automatically; in-flight tokens issued with the OLD key will
   continue to verify until they expire (default 1 hour) — you do not need to revoke
   sessions in the table.
5. After 1 hour, every live token is signed by the new key. Decommission the old PEM.

### Rotating studio-server JWT secret

Studio uses HMAC256 today (sample-only — see `StudioJwt.kt`). To rotate:

1. Generate a new secret: `openssl rand -hex 64`.
2. Roll `STUDIO_JWT_SECRET` in the secrets manager.
3. Restart all studio-server pods at once (HMAC has no overlap window — every old token
   becomes invalid). Editors reauthenticate.

### Revoking a session

Both services back JWTs with a `jti` claim that maps to a row in `sessions` (sample) or
`editor_sessions` (studio). Revocation:

```sql
-- sample-server
UPDATE sessions SET revoked_at = now() WHERE id = '<jti>';

-- studio-server
UPDATE editor_sessions SET revoked_at = now() WHERE id = '<jti>';
```

The next API call carrying that token gets 401. The `validate { credential -> ... }`
block in `Main.kt` / `StudioModule.kt` consults the table on every request.

### Compromised credentials — full revoke

```sql
UPDATE sessions SET revoked_at = now() WHERE revoked_at IS NULL;
```

Combined with rotating the signing key (sample) or HMAC secret (studio), this fully
invalidates every outstanding token within one verifier-cache TTL.

---

## 6. R8 / minification (Android adopters)

Every shipping `:sdui-kmp` library bundles consumer ProGuard rules via
`gradle/proguard/sdui-consumer-rules.pro`. Adopters get them automatically — they do
NOT need to copy keep rules into their own `proguard-rules.pro`.

If R8 still strips a serializer:

1. Confirm `isMinifyEnabled = true` is on the consumer's release build.
2. Confirm the offending class lives under `dev.sdui.kmp.protocol.**` or
   `dev.sdui.kmp.widgets**.**`. Custom node types outside these packages need adopter
   rules: `-keep class com.example.app.MyCustomNode { *; }`.
3. Inspect the merged R8 config at
   `app/build/outputs/mapping/release/configuration.txt`.

The sample-android `release` build type runs R8 in CI as a regression guard — see
`./gradlew :samples:sample-android:assembleRelease`.

---

## 7. Telemetry

`SduiTelemetry` adapters ship in `:tooling-telemetry` (test recorder),
`:tooling-telemetry-otel` (OpenTelemetry), and `:tooling-telemetry-sentry` (Sentry).
Wire one — or several, via a fan-out adapter — into `SduiHost(telemetry = ...)`.

The Sentry adapter reads no Sentry-specific config of its own; configure
`Sentry.init { ... }` once in your application's startup and pass the resulting hub
(or rely on `Sentry.getCurrentHub()`). `SENTRY_DSN` is the only env var the SDK
strictly requires — see [Sentry's docs](https://docs.sentry.io/platforms/java/configuration/).

### Suggested minimum production wiring

```kotlin
Sentry.init { options ->
    options.dsn = System.getenv("SENTRY_DSN")?.takeIf { it.isNotBlank() } ?: return@init
    options.release = System.getenv("APP_RELEASE")
    options.environment = System.getenv("APP_ENV") ?: "production"
    options.tracesSampleRate = 0.1
}
val telemetry = SentryTelemetry()
SduiHost(screen, registry, telemetry = telemetry)
```

Empty `SENTRY_DSN` → SDK is a no-op. Safe in CI / dev.

---

## 8. Common operational tasks

| Task | Command |
|------|---------|
| Tail structured logs | `kubectl logs -f deploy/sample-server` (Logback emits one JSON line per event with `request_id`, `editor_id`, `level`, `logger`, `msg`) |
| Drain a pod | `kubectl drain <node> --ignore-daemonsets` — Ktor's shutdown hook (`embeddedServer(...).start(wait = true)`) flushes in-flight requests during the SIGTERM grace period (default 30 s; bump via k8s `terminationGracePeriodSeconds`) |
| Bump pool size under load | Set `JDBC_MAX_POOL_SIZE=20` and rolling-restart. HikariCP refuses to grow live |
| Inspect live socket counts | `curl :8080/metrics \| grep ktor_websocket` — gauge `ktor_websocket_active_connections` |

---

## 9. Where things live

- Server entrypoints: `samples/sample-server/src/main/kotlin/.../Main.kt`,
  `studio-server/src/main/kotlin/.../Main.kt`, `studio-server/.../StudioModule.kt`.
- Health/readiness wiring: `samples/sample-server/.../db/ReadinessRoute.kt`.
- Metrics wiring: `samples/sample-server/.../MetricsModule.kt`,
  `studio-server/.../MetricsModule.kt`.
- JWT issuance: `auth-rs256/.../Rs256JwtIssuer.kt`,
  `studio-server/.../auth/StudioJwt.kt`.
- Session revocation: `samples/sample-server/.../db/SessionStore.kt`,
  `studio-server/.../db/EditorSessionStore.kt`.

For the SLO catalogue see [slos.md](slos.md). For incident-response procedures see
[incident-response.md](incident-response.md). For baseline-profile mechanics see
[baseline-profiles.md](baseline-profiles.md).
