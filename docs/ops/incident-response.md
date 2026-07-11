# Incident response — sdui-kmp servers

Audience: on-call engineer paged at 03:00. Read [runbook.md](runbook.md) before
this document; this one assumes you know how to deploy and shell into the
services.

## Severity ladder

| Sev | Definition | First response |
|-----|------------|----------------|
| **SEV-1** | User-perceivable outage: `/screens/*` returning 5xx, login broken, all live sockets dead | Page primary + secondary. Bring up an incident channel. Status page red. |
| **SEV-2** | Partial degradation: SLO budget burning > 14×, single editor locked out, p99 spiked above 500 ms | Page primary. Status page yellow. |
| **SEV-3** | Visible-but-tolerable issue: a single screen failing to validate, slow login, R8 keep-rule miss in a release candidate | Open ticket, no page outside business hours. |

## Escalation flow

1. Acknowledge the page within 5 minutes.
2. Page primary on-call. If unreachable in 10 minutes, page secondary.
3. SEV-1 only: page the platform lead at `+30 min` if not yet recovered.
4. Open `#incident-<short-name>` in chat. Stream every action you take into it
   so the post-mortem author has a single source of timeline.
5. Update the public status page if the SEV-1 / SEV-2 user-impact criteria are
   met.

After resolution: write a blameless post-mortem within 48 hours. Template
lives at `docs/incident/<date>-<slug>.md`.

---

## Common failure modes

The table below maps Prometheus / Grafana alerts to the underlying root cause
and the recovery procedure. All Grafana panel names refer to the dashboard at
[grafana-dashboard.json](grafana-dashboard.json).

### F1 — Database connection exhaustion

**Symptom**

* `hikaricp_connections_active / hikaricp_connections_max` over 0.95 for
  more than 5 minutes.
* Sample-server returns 503 from `/readiness`; clients see 5xx on
  `/screens/*`.
* Logback emits `HikariPool-1 - Connection is not available, request timed out`.

**Root causes** (in order of likelihood)

1. Postgres is up but slow — a long-running query is holding connections.
2. Pool was sized for a baseline traffic pattern that has since grown.
3. A recently merged route introduced a leak (transaction not closed).

**Recovery**

```bash
# 1. Confirm Postgres is reachable from the pod.
kubectl exec deploy/sample-server -- /opt/app/bin/sample-server-cli probe-db

# 2. Snapshot active queries.
psql -h pg -U sdui -d sdui_sample -c "SELECT pid, age(now(), query_start), state, query
                                       FROM pg_stat_activity
                                       WHERE state <> 'idle'
                                       ORDER BY query_start;"

# 3. Cancel long queries (> 30s).
psql -h pg -U sdui -d sdui_sample -c "SELECT pg_cancel_backend(pid)
                                       FROM pg_stat_activity
                                       WHERE state <> 'idle'
                                         AND age(now(), query_start) > interval '30 seconds';"

# 4. Bump the pool, rolling restart.
kubectl set env deploy/sample-server JDBC_MAX_POOL_SIZE=20
kubectl rollout restart deploy/sample-server
```

Track the latest leak culprit in the post-mortem; revert the offending
deploy if the spike correlates with a release.

### F2 — JWT key rotation gone wrong

**Symptom**

* `/auth/login` succeeds, but every subsequent call to `/screens/*` returns
  401 with `outcome=signature_invalid` (S4 SLO firing).
* Federated services calling the API see the same 401.

**Root causes**

1. Updated `RSA_PRIVATE_KEY` and `RSA_PUBLIC_KEY` are mismatched (stale
   public key).
2. `RSA_KEY_ID` was changed but JWKS clients have the old `kid` cached.
3. Restart was not rolling — the secret got overwritten before the JWKS
   document republished.

**Recovery**

```bash
# 1. Confirm the JWKS endpoint actually returns the new key.
curl -s https://api.example.com/.well-known/jwks.json | jq '.keys[].kid'

# 2. If kid is wrong, redeploy with the corrected RSA_PUBLIC_KEY env. Do NOT
#    change RSA_PRIVATE_KEY again — the issuer must keep signing with the
#    same key.
kubectl set env deploy/sample-server RSA_PUBLIC_KEY="$(cat /run/secrets/jwt_public.pem)"

# 3. Force-bust JWKS caches in dependent services. Rolling restart is the
#    safe option; calling the cache-bust endpoint is faster but service-
#    specific.

# 4. Revoke any sessions issued with the broken key as a precaution.
psql -h pg -U sdui -d sdui_sample <<'SQL'
UPDATE sessions SET revoked_at = now() WHERE issued_at > now() - interval '1 hour';
SQL
```

If the private key was rotated AND the rollout missed pods, run a
`kubectl rollout undo deploy/sample-server` to restore the previous (still
working) key, then retry the rotation following [runbook.md §5](runbook.md#5-jwt-key-management).

### F3 — WebSocket disconnect storm (live publish)

**Symptom**

* `ktor_websocket_active_connections` drops sharply across the fleet, then
  reconnects in waves.
* Studio publishes succeed but clients show stale screens.

**Root causes**

1. A network blip between clients and the LB (transient).
2. The pod went OOMKilled and restarted — every connected client reconnected
   at once.
3. A misconfigured `terminationGracePeriodSeconds` drains the pod faster
   than the WebSocket close handshake can complete.

**Recovery**

```bash
# 1. Check pod restarts in the last hour.
kubectl get pods -l app=sample-server --no-headers -o custom-columns=NAME:.metadata.name,RESTARTS:.status.containerStatuses[0].restartCount

# 2. If OOMKilled, check the heap-used panel. Bump JVM heap.
kubectl set env deploy/sample-server JAVA_OPTS="-Xmx2g"

# 3. If grace is too short, raise it.
kubectl patch deploy sample-server -p '{"spec":{"template":{"spec":{"terminationGracePeriodSeconds":60}}}}'

# 4. Add jitter on the client to spread the reconnect wave (transport-live
#    backoff is already exponential; a small full-jitter addition prevents
#    thundering herds).
```

Until the per-process publisher gap is closed, a studio-server pod restart
also drops every studio→sample reflection. That's expected — note it on the
status page when you bounce studio.

### F4 — R8-stripped serializer in a release build

**Symptom**

* Adopter reports `SerializationException: Serializer for class 'X' is not found`
  in their release-mode Android app.
* The same code works in debug builds.

**Root causes**

1. The custom UiNode / Action subclass lives outside `dev.sdui.kmp.protocol.**`
   and `dev.sdui.kmp.widgets**.**`, so the consumer keep rules don't match.
2. The adopter's app overrides `proguard-rules.pro` with a `-allowaccessmodification`
   or `-repackageclasses` rule that overrides our keep.
3. R8 minor-version regression introduced a new keep-rule semantic.

**Recovery**

```bash
# 1. Have the adopter share build/outputs/mapping/release/configuration.txt.
#    Look for `Note: keep rule for ... ignored due to ...` lines.

# 2. Add an explicit keep rule for the custom class:
#    -keep class com.example.app.MyCustomNode { *; }
#    -keep class com.example.app.MyCustomNode$$serializer { *; }

# 3. As a regression guard, the sample-android `assembleRelease` runs R8
#    in CI — check that build is green.
./gradlew :samples:sample-android:assembleRelease
```

Long-term: if a new framework type lives outside the standard packages,
update `gradle/proguard/sdui-consumer-rules.pro` to match it. Adopters
pick up the change on the next dependency bump.

### F5 — Sentry breadcrumb buffer flooded

**Symptom**

* Sentry events arrive missing recent breadcrumbs.
* On-call inspecting a captured event sees only one or two breadcrumbs in
  the trail despite many `onNodeRendered` calls.

**Root cause**

`onNodeRendered` adds a breadcrumb per node. On a screen with hundreds of
nodes, the per-event breadcrumb buffer (default 100) overflows and the
oldest entries drop. The screen-render and action breadcrumbs at the top
of the stack are exactly the ones we want to keep.

**Recovery**

In the host's `Sentry.init { ... }`:

```kotlin
Sentry.init { options ->
    // Keep the last 200 breadcrumbs so a node-heavy screen doesn't push out
    // the screen + action context. Sentry caps this at 1000.
    options.maxBreadcrumbs = 200
    // Or filter out DEBUG-level breadcrumbs entirely (the `onNodeRendered`
    // path emits at DEBUG):
    options.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
        if (crumb.level == io.sentry.SentryLevel.DEBUG) null else crumb
    }
}
```

The framework adapter intentionally emits node renders at DEBUG so adopters
can filter them with one line. INFO-level (screen + action) breadcrumbs are
preserved by either approach above.

---

## Recovery primitives

These are the tools you should know how to reach for during an incident.
Keep them sharp by exercising them in quarterly chaos drills.

* `kubectl exec deploy/<service> -- jcmd 1 GC.heap_dump /tmp/heap.hprof` — heap dump a hot pod.
* `kubectl exec deploy/<service> -- jstack 1` — thread dump.
* `kubectl logs deploy/<service> --previous` — read the OOMKilled predecessor's logs.
* `psql -h pg -U sdui -c "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"` — connection-by-state snapshot.
* `curl :8080/metrics | grep -E 'jvm_gc_pause_seconds|jvm_memory_used' ` — quick local-pod health.
* `curl :8080/.well-known/jwks.json | jq` — confirm what JWKS clients see.

---

## Escalation contacts

Maintained in the team handbook (kept out of git so off-hours rotations don't
drift). Page targets:

| Role | Routing |
|------|---------|
| Primary on-call | PagerDuty `sdui-kmp-primary` |
| Secondary | PagerDuty `sdui-kmp-secondary` |
| Platform lead | PagerDuty `sdui-kmp-lead` (manual page only) |
| Database ops | PagerDuty `pg-ops` |
