# Architecture Decision Records

Non-obvious decisions made during Phases 1–8. Each record captures the context that made
the choice plausible, the options weighed, and the consequences we're accepting.

Format: [MADR](https://adr.github.io/madr/), lightly pruned. Status is "accepted" unless a
later record supersedes it.

| # | Title |
|---|-------|
| [0001](0001-value-t-stores-jsonelement.md) | `Value<T>` stores `JsonElement` on the wire |
| [0002](0002-live-event-consolidation.md) | `LiveEvent` is two shapes, not five |
| [0003](0003-submit-handler-seam.md) | Submit handler is a SAM in `:runtime`, not Ktor-backed |
| [0004](0004-optimistic-rollback-via-snapshot.md) | Optimistic-update rollback captures prior values, including unset |
| [0005](0005-native-surface-fallback.md) | `NativeSurface` falls back via `UiNode.fallback` on unknown kinds |
| [0006](0006-in-house-stack-navigator.md) | In-house `StackNavigator`, not Voyager/Decompose |
| [0007](0007-fixtures-hand-authored.md) | Fixture JSON is hand-authored, not generated |
| [0008](0008-telemetry-lives-in-runtime.md) | `SduiTelemetry` interface lives in `:runtime`, not `:tooling-telemetry` |
| [0009](0009-binary-format-deferred.md) | Protobuf opt-in transport deferred (`JsonElement` serializer is JSON-only) |
| [0010](0010-a11y-semantics-wiring.md) | A11y semantics wired via a single `Modifier.applyA11y` helper |
| [0016](0016-prometheus-metrics-endpoint.md) | Prometheus `/metrics` endpoint on sample-server and studio-server |
| [0017](0017-sentry-telemetry-adapter.md) | Sentry adapter for `SduiTelemetry` |
