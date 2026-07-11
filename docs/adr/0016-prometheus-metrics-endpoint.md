# ADR-0016: Prometheus `/metrics` endpoint on sample-server and studio-server

* Status: Accepted
* Date: 2026-04-25
* Deciders: framework operations
* Supersedes / superseded by: —

## Context

The framework had no first-party operational telemetry on either server until
this change. Adopters could install their own metrics, but operators running
the bundled sample-server / studio-server in production had no consistent
way to:

* Read p99 / p95 latency on `/screens/*` and `/admin/*`.
* Watch JVM heap / GC / thread state.
* Build dashboards or alert on SLO burn (see [docs/ops/slos.md](../ops/slos.md)).

A prior version of `:tooling-telemetry-otel` covered the SDK-side renderer /
dispatcher events but did not instrument the HTTP transport on the server side.
That gap is what this ADR closes.

## Decision

Both servers (`:samples:sample-server` and `:studio-server`) install the Ktor
[`MicrometerMetrics`](https://ktor.io/docs/server-metrics-micrometer.html)
plugin against a single process-wide
`io.micrometer.prometheusmetrics.PrometheusMeterRegistry`, expose `GET
/metrics` returning the Prometheus text-format scrape, and register the
standard suite of JVM meter binders (`JvmMemoryMetrics`, `JvmGcMetrics`,
`JvmThreadMetrics`, `ClassLoaderMetrics`, `ProcessorMetrics`).

The wiring lives in two new files:

* `samples/sample-server/.../MetricsModule.kt`
* `studio-server/.../MetricsModule.kt`

Both call `installMetrics()` before the rest of the production prelude (so the
request timer wraps every handler) and mount `installMetricsRoute()` alongside
`/health`.

## Why Micrometer + Prometheus, not OpenTelemetry?

`:tooling-telemetry-otel` already ships an OpenTelemetry adapter for the
**runtime** (renderer / dispatcher events). The server-side metrics endpoint
serves a different audience — operators who want to scrape JVM gauges and HTTP
timers in the format Prometheus speaks natively, without an OTLP collector
hop.

Micrometer's Prometheus registry is the de-facto standard for JVM Ktor apps,
the binder set is exhaustive, and it requires no out-of-process collector. The
adapter surface is small (one plugin install, one route, one registry), so we
take it as the primary metrics export and keep OpenTelemetry as the runtime-
side adapter.

If a future deployment wants OTLP-flavoured server metrics, Micrometer's
`OtlpMeterRegistry` is a one-line swap — the `Application.installMetrics`
helper accepts any `MeterRegistry`.

## Why a single `/metrics` endpoint per service?

Some operators ask for a separate "internal" port (typically 9090) so the
metrics endpoint is firewalled away from public traffic. We considered:

1. Two ports per service. Rejected: doubles the deployment surface (extra
   load-balancer rule, extra Prometheus scrape config) for a security control
   that is solved at the network layer (a `NetworkPolicy` restricting the
   `/metrics` path to the Prometheus pod is one yaml file).
2. Token-protected `/metrics`. Rejected: Prometheus's bearer-token support is
   awkward to rotate, and an attacker with read access to JVM gauges has
   nothing actionable.
3. Public `/metrics` (chosen). Operators apply a NetworkPolicy or LB-level ACL
   to restrict scrape access; documented in the runbook.

## Consequences

* Both servers gain a new dependency: `io.micrometer:micrometer-registry-prometheus`
  (1.13.x) plus `io.ktor:ktor-server-metrics-micrometer` (already on the Ktor
  3.x plane). Adds ~600 KB to each shaded distribution.
* The `/metrics` route is **public by default**. Operators in security-
  sensitive deployments must apply network-level restrictions; see runbook §6.
* SLO definitions in [docs/ops/slos.md](../ops/slos.md) reference Prometheus
  metric names directly. If the exposed metric set changes (e.g. Micrometer
  upgrade renames a label), the SLO queries must be updated in the same PR.
* The Grafana starter dashboard at [docs/ops/grafana-dashboard.json](../ops/grafana-dashboard.json)
  ships in-tree so adopters can import and customise.

## Alternatives considered

* **Pull from the JVM JMX bean directly** with `jmx_exporter`. Works but
  requires a sidecar; the in-process Micrometer path is simpler.
* **Push to a StatsD/Graphite collector**. Rejected: adds a hard dependency
  on an external aggregator for what is primarily an SLO-monitoring use case.
* **No metrics endpoint, rely on log mining.** Rejected: log-derived latency
  histograms have order-of-magnitude higher overhead than a Prometheus scrape.

## References

* [docs/ops/runbook.md](../ops/runbook.md) — operator-facing usage.
* [docs/ops/slos.md](../ops/slos.md) — SLO catalogue.
* [docs/ops/grafana-dashboard.json](../ops/grafana-dashboard.json) — starter dashboard.
* [ADR-0017](0017-sentry-telemetry-adapter.md) — the parallel decision for the
  client-side Sentry adapter.
