# Service-level objectives — sdui-kmp servers

Proposed SLOs covering the public surface of `:samples:sample-server` and
`:studio-server`. Targets are starting points calibrated to the workload scale
the project anticipates in its first deployment cycle; revise quarterly with
real traffic data.

Every SLO maps to a Prometheus query backed by the `/metrics` endpoint exposed
on both services (see `gradle/libs.versions.toml` `micrometerPrometheus`).

---

## SLO catalogue

### S1 — Screen GET latency

> **99% of `GET /screens/{id}` requests complete in under 200 ms over a rolling
> 30-day window.**

* Service: sample-server
* Why: Screen GETs are the user-perceivable critical path — every cold start,
  every navigation, every live-reload reconciliation hits this route.
* Error budget: 1% of requests (~7 hours of slow tail per 30-day window
  assuming uniform load). When 50% of the budget is burned in the first 7
  days, page on-call.

```promql
# p99 latency over 5 min, in seconds. Convert ms → s by dividing by 1000.
histogram_quantile(
  0.99,
  sum(rate(ktor_http_server_requests_seconds_bucket{
    application="sample-server",
    route=~"/screens/.*"
  }[5m])) by (le, route)
) < 0.2
```

### S2 — Screen GET error rate

> **Less than 0.1% of `GET /screens/{id}` requests return 5xx over a rolling
> 7-day window.**

* Service: sample-server
* Why: 5xx is hard failure — there is nothing the client can usefully do.
  401 / 403 are excluded because they are part of the auth contract.

```promql
sum(rate(ktor_http_server_requests_seconds_count{
  application="sample-server",
  route=~"/screens/.*",
  status=~"5.."
}[5m]))
/
sum(rate(ktor_http_server_requests_seconds_count{
  application="sample-server",
  route=~"/screens/.*"
}[5m]))
< 0.001
```

### S3 — Live publish broadcast tail latency

> **The 99th-percentile time from a Studio publish landing to a subscribed
> client receiving the corresponding `LiveEvent.TreePatchEvent` is under 500 ms.**

* Service: studio-server (publish side) → sample-server / clients
  (subscription side, via `/live/screens/{id}`)
* Why: live publish is the editor's feedback loop. Above ~500 ms the editor
  starts double-clicking publish, which doubles audit-log volume and risks
  conflicting publishes.
* Currently per-process — when a Redis-backed publisher lands, the SLO covers
  the cross-pod path too (see runbook §1).

```promql
histogram_quantile(
  0.99,
  sum(rate(ktor_websocket_publish_seconds_bucket{
    application="studio-server",
    route="/live/screens/.*"
  }[5m])) by (le)
) < 0.5
```

(Histogram is exported by `WebSocketLivePublisher.broadcast` once we add the
custom timer — tracked as a follow-up; until then approximate by alerting on
absence of `ktor_websocket_active_connections` for any `/live/screens/{id}`
route over 30 s after a publish.)

### S4 — JWT verification error rate

> **Less than 0.01% of `Authorization: Bearer` checks fail with a verifier
> error (signature mismatch, malformed token) over a rolling 24-hour window.**

* Service: sample-server, studio-server
* Why: a sustained spike on this signal is either a key-rotation foul (we
  shipped without updating the JWKS) or a credential-stuffing attack.
* Excludes the expected `401 — token expired` rate; that is governed by SLO
  S5.

```promql
sum(rate(ktor_http_server_requests_seconds_count{
  status="401",
  outcome="signature_invalid"
}[5m]))
/
sum(rate(ktor_http_server_requests_seconds_count{
  uri=~"/screens/.*|/admin/.*"
}[5m]))
< 0.0001
```

The `outcome=signature_invalid` label requires the verifier to tag the
request — see the WIP `RequestLogPlugin` extension referenced in
`incident-response.md` §3.

### S5 — Auth login p95 latency

> **95% of `POST /auth/login` requests complete in under 400 ms.**

* Service: sample-server
* Why: bcrypt/keypair work happens here; a regression to ~1 s typically
  means the JWT issuer is rebuilding the key on every call (cache miss
  loop) or the DB is starved.

```promql
histogram_quantile(
  0.95,
  sum(rate(ktor_http_server_requests_seconds_bucket{
    application="sample-server",
    route="/auth/login"
  }[5m])) by (le)
) < 0.4
```

### S6 — DB connection saturation

> **HikariCP active-connections gauge stays under 80% of `maximumPoolSize`
> on a 10-minute moving average.**

* Service: sample-server, studio-server
* Why: a sustained 80%+ pool means the next traffic spike will queue at the
  HikariCP gate, which manifests as request-timeout fires and looks like a
  Postgres outage. Fix by tuning `JDBC_MAX_POOL_SIZE` upward or splitting
  read-only queries onto a replica.

```promql
avg_over_time(hikaricp_connections_active[10m])
/ on(application, pool)
avg_over_time(hikaricp_connections_max[10m])
< 0.8
```

`hikaricp_connections_*` come from the HikariCP Micrometer integration —
HikariCP registers the meters automatically when given a Micrometer
`MeterRegistry`. Today both servers wire Hikari without that bridge; adding
it is a one-line `metricRegistry = sampleMetricsRegistry` change in `Db.kt`
/ `StudioDatabase.kt`. Tracked as a follow-up.

---

## Burn-rate alert pattern

For SLOs that target a 30-day window (S1, S2), use Google's burn-rate
formula: page when the budget is being consumed at 14× the steady rate over
5 minutes AND 1× over 1 hour.

```promql
# 5m fast burn × 14 (3.6h to exhaust 30-day budget). Page if true.
(
  sum(rate(http_requests_failed[5m])) / sum(rate(http_requests_total[5m]))
) > 14 * 0.001  # 14× the 0.1% target
AND
(
  sum(rate(http_requests_failed[1h])) / sum(rate(http_requests_total[1h]))
) > 0.001
```

Tickets vs pages: SLO breach over a 24-hour window opens a ticket; over 30
minutes opens a page. Adjust to local pager-fatigue tolerance.

---

## Reviewing SLOs

* Owner: framework operations lead.
* Cadence: quarterly. SLOs that fire less than twice a quarter are too
  loose; SLOs that consume their budget every quarter are too strict.
* Source data: Prometheus retention is 15 d in the default config; copy
  exemplars to long-term storage (Cortex / Mimir / Thanos) before adjusting.
