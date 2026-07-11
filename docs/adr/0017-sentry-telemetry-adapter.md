# ADR-0017: Sentry adapter for `SduiTelemetry`

* Status: Accepted
* Date: 2026-04-25
* Deciders: framework operations
* Supersedes / superseded by: —

## Context

`SduiTelemetry` (in `:runtime`) is the framework's seam for routing renderer
and dispatcher events into a host-app analytics pipeline. Two adapters
existed before this change:

* `RecordingTelemetry` (in `:tooling-telemetry`) — test-only, captures every
  call into per-event lists.
* `OpenTelemetryTelemetry` (in `:tooling-telemetry-otel`) — JVM-only, maps
  events to OTel spans / metrics / log records.

[ADR-0008](0008-telemetry-lives-in-runtime.md) anticipated additional
adapters living under `:tooling-telemetry` or as siblings. The next one most
adopters ask for is **Sentry** — it is the dominant exception-tracking SaaS
on JVM and Android, and the lack of a first-party adapter forces every
adopter to write the same mapping boilerplate.

## Decision

Add a new `:tooling-telemetry-sentry` JVM-only library that ships
`SentryTelemetry`, a parallel `SduiTelemetry` implementation routing events
into the Sentry SDK's `IHub`.

Mapping:

| Event | Sentry signal |
|-------|---------------|
| `onScreenRendered` | INFO breadcrumb, category `sdui.screen`, type `navigation` |
| `onNodeRendered` | DEBUG breadcrumb, category `sdui.node` (filterable via `beforeBreadcrumb`) |
| `onUnknownNode` | WARNING breadcrumb + WARNING `SentryEvent` capture, tagged with node type and trace |
| `onActionDispatched` | INFO breadcrumb, category `sdui.action`. **Discriminator only**, no payload |
| `onBindingError` | WARNING breadcrumb + WARNING `SentryEvent` capture, tagged with state path / expected / got |

Constructor accepts an explicit `IHub` for tests; the default uses
`Sentry.getCurrentHub()` so adopters get the process-wide hub configured by
their `Sentry.init` block.

## Why JVM-only?

The Sentry SDK has separate Java (`io.sentry:sentry`) and native
(`sentry-android`, `sentry-cocoa`) artifacts with different surface APIs.
Cross-target binding via expect/actual would mean writing three independent
adapters and a portable façade — out of scope for this phase. A JVM-only
target covers Android (the SDK shares the Java artifact via desugaring) and
the JVM Compose / desktop sample.

iOS / Wasm hosts wanting Sentry today wire it in their own application
layer. A future ADR can revisit this if the Sentry Cocoa SDK gains a
sufficiently parallel API surface.

## Why Sentry SDK 7.x, not 8.x?

Sentry 8.x renamed `IHub` / `Sentry.getCurrentHub()` to
`IScopes` / `Sentry.getCurrentScopes()` and introduced an in-place migration
helper. The migration is mechanical but breaks every adopter currently on
the LTS 7.x line. Pinning 7.22.x keeps the migration cost on us — when the
ecosystem broadly moves to 8.x, we issue a new module version with the API
update under semver-major rules.

## Why discriminator-only on actions?

`Action` subtypes carry user payload on the wire: `Action.UpdateState.value`
contains the new state value, `Action.Submit.payload` contains form fields,
etc. Including the action's stringified content in a breadcrumb leaks user
data into Sentry's transport, which is both a privacy and a compliance
problem.

The adapter sends only `action::class.simpleName` (e.g. `Navigate`,
`Submit`, `UpdateState`) — sufficient for diagnosing "what kind of action
fired before the crash" without the payload risk. We deliberately avoid
reading the `@SerialName` annotation so we never have to depend on
`kotlin-reflect`.

## Consequences

* New shipping module: `:tooling-telemetry-sentry`. Increases the
  `verifyRelease` shipping count by 1.
* New dependency: `io.sentry:sentry:7.22.6`. JVM-only.
* Adopters that only want exception capture (no breadcrumbs) can wire
  `Sentry.init` without `SentryTelemetry`; the adapter is purely additive.
* The breadcrumb buffer can flood on node-heavy screens — see
  [docs/ops/incident-response.md](../ops/incident-response.md) F5 for the
  tuning knob.

## Alternatives considered

* **Generic dispatch interface** that lets the adopter map events to any
  exception tracker. Rejected: the indirection adds work for adopters who
  use Sentry, the most common case. We can add a fan-out adapter later if
  multiple sinks are common in practice.
* **Add Sentry mappings to `:tooling-telemetry-otel`.** Rejected: OTel and
  Sentry have different signal models (Sentry is breadcrumb-shaped, OTel
  is span/metric-shaped). Conflating them would force one or the other to
  fit the wrong shape.
* **Server-side wiring.** Out of scope for this ADR. The ops adapters in
  `:samples:sample-server` and `:studio-server` could add Sentry support
  via their own `SentryServletInitializer` + `Sentry.init`; the
  `SduiTelemetry` adapter is for the **renderer / dispatcher** events only.

## References

* [ADR-0008](0008-telemetry-lives-in-runtime.md) — `SduiTelemetry` lives in `:runtime`.
* [ADR-0016](0016-prometheus-metrics-endpoint.md) — the parallel decision for the server-side metrics export.
* `tooling-telemetry-sentry/src/main/kotlin/.../SentryTelemetry.kt` — the adapter source.
