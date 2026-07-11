# ADR 0008 — `SduiTelemetry` interface lives in `:runtime`, not `:tooling-telemetry`

**Status:** accepted (Phase 7, M7)

## Context

[ARCHITECTURE.md](../../ARCHITECTURE.md) puts `:tooling-telemetry` at Tier 0 and describes
it as the home of "Telemetry hook interfaces". The implication is that `SduiTelemetry` (the
interface host apps implement to route events to analytics) belongs there.

In the implementation that landed in Phase 1 (M1.9), `SduiTelemetry` was placed in
`:runtime/commonMain/.../SduiTelemetry.kt` so `RenderNode` and `DefaultActionDispatcher`
could call into it without an extra module boundary.

## Problem

Moving `SduiTelemetry` from `:runtime` to `:tooling-telemetry` at this point requires:

- Re-importing across every sample app (`dev.sdui.kmp.runtime.SduiTelemetry` → `dev.sdui.kmp.tooling.telemetry.SduiTelemetry`).
- Inverting the dep edge: `:runtime` would need `api(project(":tooling-telemetry"))`, which reverses Tier 2 → Tier 0 direction. Our module rules bar this.

## Decision

`SduiTelemetry`, `NoopTelemetry`, and `LocalTelemetry` stay in `:runtime`.
`:tooling-telemetry` depends on `:runtime` (api) and ships **implementations** — specifically
`RecordingTelemetry`, the test double.

## Rationale

- Preserves the module-rule invariant that lower tiers never depend on higher tiers.
- Zero import churn across the codebase.
- `:tooling-telemetry`'s role becomes "telemetry test doubles and future adapters" instead
  of "home of the interface". Well-scoped in practice.

## Consequences

- Minor deviation from the architecture doc's stated module purpose.
  [ARCHITECTURE.md](../../ARCHITECTURE.md) should be updated to reflect this once the other
  Phase 7 decisions land — one coordinated doc update at the end of the phase.
- If a future real-telemetry adapter (OpenTelemetry, Datadog, ...) wants to be a Tier 0
  module, it lives under `:tooling-telemetry` or beside it; the split doesn't require a
  rename.
