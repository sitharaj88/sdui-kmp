# GDPR & privacy — sdui-kmp

This document describes what data the sdui-kmp framework handles, where it's stored, and
how an adopter integrating sdui-kmp into a SaaS product should think about GDPR roles
and obligations. It is **not** a replacement for legal counsel — it's a starting point
for the controller's privacy review.

## Data flows in scope

sdui-kmp is a **server-driven UI** framework. The data flows are:

```
+-----------+        screen JSON         +-----------+
|  server   |  ------------------------> |  client   |
|  (JVM)    |                            | (Android, |
|           |  <----- Action JSON -----  |  iOS,     |
|           |                            |  Desktop, |
|           |                            |  Wasm)    |
+-----------+                            +-----------+
     |                                          |
     | (optional)                               | (optional)
     v                                          v
  Postgres                                  SduiTelemetry
  (studio audit log,                        (analytics / OTel /
   submit idempotency,                       crash reporters)
   form drafts)
```

Three categories of data:

1. **UI tree JSON** — the protocol payload. May contain bound state references and
   user-specific values *if the server places them there*. The framework itself is
   schema-only; it has no opinion on whether the server stuffs PII into a `Value.Literal`
   or keeps PII out by binding `Value.Bind(StatePath)` against client-local state. See
   [data-minimization.md](data-minimization.md).
2. **State store contents** — `Value.Bind(StatePath)` reads from a per-screen
   `StateStore` initialised from the protocol's `Screen.initialState` JSON. The store
   lives in client memory only and is discarded when the screen leaves composition
   (or by the navigator on Back). It is **never** transmitted back to the server unless
   the server-emitted `Action.Submit` explicitly references the path in its payload map.
3. **Telemetry events** — `SduiTelemetry` hooks emit `onScreenRendered`,
   `onNodeRendered`, `onUnknownNode`, `onActionDispatched`, `onBindingError`. The
   default implementation is `NoopTelemetry` (no I/O). Adopters wire their own
   implementation. See the consent gating in [`ConsentAwareTelemetry`](../../tooling-telemetry/src/commonMain/kotlin/dev/sdui/kmp/tooling/telemetry/ConsentAwareTelemetry.kt)
   and ADR [0015](../adr/0015-consent-aware-telemetry.md).

## Controller vs processor

For a SaaS adopter:

| Role | Who plays it | What they're responsible for |
|---|---|---|
| **Data controller** | The SaaS adopter | Decides what fields go into the screen tree, what state paths the client binds, what `Action.Submit` payloads carry to the backend. Owns the privacy notice, consent collection, and DSARs. |
| **Data processor** | sdui-kmp itself, when used as a library | Processes whatever the controller ships. The framework has no telemetry of its own; it does not phone home. |
| **Sub-processor** | The adopter's chosen telemetry / analytics / crash reporter (Datadog, Sentry, OTel collector, ...) | Whatever the adopter wires through `SduiTelemetry`. The framework gates emission on `LocalConsentSource.isGranted(ConsentScope.Telemetry)` so the adopter's CMP choice flows through automatically. |

If sdui-kmp is **forked or hosted as a managed service**, the operator of that service
becomes a processor and must run their own DPIA.

## Data residency

The framework itself is platform code; residency is determined by:

* The server: the JVM application that hosts `:server`. Adopters self-host or run on a
  cloud region of their choosing.
* The studio backend (`:studio-server`): if the adopter runs the editor admin app, its
  Postgres holds drafts + audit-log entries. Default retention: see
  `studio-server/src/main/kotlin/.../config/RetentionConfig.kt` (defaults are
  conservative — 90 days on audit-log entries, indefinite on drafts until deleted).
* The sample HTTP cache (`transport-cache`): per-platform on-device stores (DataStore
  on Android, NSURLCache on iOS, OS-default cache on Desktop, IndexedDB on Wasm). All
  client-local; nothing crosses borders by virtue of using sdui-kmp.

## The "Action is data, not code" property is privacy-positive

A core sdui-kmp invariant ([VISION.md](../../VISION.md) #4) is that user interactions
encode as serializable `Action` data classes, not lambdas. This has direct privacy
benefits:

* **Auditability**: every action a user fired can be replayed, logged, or aggregated
  without dragging in a closure-captured environment that might leak references to
  other user data.
* **Off-device queueing**: actions can be queued offline, encrypted at rest, and
  replayed exactly once when connectivity returns — without keeping a live VM with
  potentially-sensitive captured state.
* **Right-to-erasure (Article 17)**: deleting a user's queue is a single SQL DELETE
  on a row of structured data, not "find every closure that referenced this user".

## Retention defaults

The shipped sample server (`samples/sample-server`) demonstrates conservative defaults
that adopters should adjust to fit their notice:

| Table | Default retention |
|---|---|
| `submit_idempotency` | 7 days. After that, replays of the same key fail closed and are re-executed end-to-end. |
| `session` (if the adopter wires session storage) | 30 days of inactivity, hard delete. |
| `studio_audit` | 365 days. Records who edited which screen; PII is the editor user ID, not the end user's data. |
| `form_draft` | 30 days, hard delete. Holds in-progress submissions before the user hits Submit. |

These are framework defaults. Adopters MUST review and adjust to match their privacy
notice. The framework does not auto-enforce the schedule — adopters wire it through
their normal database housekeeping (cron, pg_partman, etc.).

## Data Subject Rights (DSAR) workflow

Because sdui-kmp does not warehouse user data of its own, DSARs are handled entirely by
the adopter's existing DSAR pipeline. The framework offers no special primitive for
"export every value bound by user X across every screen they visited" — that's
intentional, because such a primitive would require warehousing those values, which the
framework explicitly declines to do.

## See also

* [data-minimization.md](data-minimization.md) — guidelines for putting PII into
  protocol payloads carefully.
* [dpia.md](dpia.md) — DPIA template for adopters.
* [ADR 0015](../adr/0015-consent-aware-telemetry.md) — design decision behind the
  consent primitive.
