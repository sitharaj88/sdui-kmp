# ADR 0015 — Consent-aware telemetry

**Status:** accepted (M-P26 compliance pass)

## Context

`SduiTelemetry` is a synchronous interface in `:runtime` that hosts wire to an analytics
or observability pipeline (`tooling-telemetry-otel`, `tooling-telemetry-sentry`, an
in-house sink, etc.). The runtime invokes it on every screen render, node render, action
dispatch, unknown-node fallback, and binding error.

GDPR (and similar regimes — CCPA, LGPD, PIPL) require that telemetry covered by their
definitions of "personal data" is gated on the user's consent. Historically every adopter
had to reimplement the gating: wrap their telemetry instance in a per-org decorator that
checked their CMP, plumb that through their host, and remember to renew the check on
preference change.

The framework needs to provide:

1. A first-class concept of consent scopes (analytics, telemetry, personalization, crash).
2. A composition-aware way to swap telemetry on/off based on the active consent.
3. A path for non-Composable Kotlin code (server-side, tests, headless tooling) to apply
   the same gating without dragging in Compose.

## Decision

Two parts:

1. **Pure-Kotlin primitive** in `:runtime`:
   ```kotlin
   public enum class ConsentScope { Analytics, Telemetry, Personalization, Crash }
   public interface ConsentSource { public fun isGranted(scope: ConsentScope): Boolean }
   public object NoConsent : ConsentSource { /* always false */ }
   public object FullConsent : ConsentSource { /* always true */ }
   public val LocalConsentSource: ProvidableCompositionLocal<ConsentSource> =
       staticCompositionLocalOf { NoConsent }
   ```

   `LocalConsentSource` defaults to `NoConsent` so a host that forgets to wire its CMP
   fails closed — telemetry is suppressed until the host opts in.

2. **Composable + plain-Kotlin decorator** in `:tooling-telemetry`:
   ```kotlin
   public class ConsentAwareTelemetry(
       private val delegate: SduiTelemetry,
       private val consent: ConsentSource,
   ) : SduiTelemetry { /* gates every override on consent.isGranted(Telemetry) */ }

   @Composable
   public fun rememberConsentAwareTelemetry(delegate: SduiTelemetry): SduiTelemetry
   ```

   The `@Composable` factory captures `LocalConsentSource.current` and rebuilds the
   wrapper when `delegate` or `consent` change. The wrapper queries consent on every
   emission, so toggling consent at runtime takes effect immediately without
   recomposition.

## Why these specific scopes

The four scopes are coarse on purpose:

* **Analytics** — first-party product-usage events.
* **Telemetry** — runtime instrumentation (renders, actions, errors).
* **Personalization** — server-side adaptation of UI trees to user history.
* **Crash** — automatic crash / non-fatal error reporting.

Most CMPs map cleanly onto these four buckets. Vendor-specific buckets ("targeted
advertising", "analytics partners", ...) belong in adopter code. The framework uses
`Telemetry` as the gate today; the others are reserved namespace for future runtime
features (the protocol does not currently consume `Personalization` or `Crash`, but
adopters can read them off the same `LocalConsentSource` from their own code).

## Why Composable AND plain-Kotlin

The SduiHost runtime is Composable, so the natural wiring point is a Composable factory:

```kotlin
val telemetry = rememberConsentAwareTelemetry(myOtelTelemetry)
SduiHost(telemetry = telemetry, ...)
```

But test fixtures and server-side tooling instantiate `SduiTelemetry` directly without a
composition. For those callers `ConsentAwareTelemetry(delegate, consent)` works as a
plain class — pass any `ConsentSource` (a CLI flag, an env var, `FullConsent` for tests).

The `:tooling-telemetry` module is the right home for the decorator because:

* `:runtime` already exposes the primitive (`ConsentSource` etc.) and should not depend
  on Compose Material 3 or any decorator framework.
* `:tooling-telemetry` already has `RecordingTelemetry` — it's the existing seam where
  decorators live.
* `:tooling-telemetry-otel` and `:tooling-telemetry-sentry` get gating for free by
  composing with `ConsentAwareTelemetry`; no per-vendor changes needed.

## Why default to `NoConsent`

A new SDK adoption typically starts with no CMP integration. If we defaulted to
`FullConsent`, every adopter would silently emit telemetry until they remembered to wire
their CMP — a privacy footgun. Failing closed forces the adopter to make the explicit
"yes, telemetry is on" choice.

Tests and sample apps that don't care override with `LocalConsentSource provides FullConsent`.

## Consequences

* The default `SduiHost` setup emits **zero** telemetry (because the composition local
  defaults to `NoConsent`) until the host overrides it. Existing tests that wire
  `SduiHost` without `LocalConsentSource` continue to work because they pass
  `NoopTelemetry` directly — no decorator in play, no gate. Once an adopter switches to
  `rememberConsentAwareTelemetry(realTelemetry)`, the gate kicks in.
* `tooling-telemetry` now depends on `compose.runtime` (for the `@Composable` factory).
  Module-tier rules still hold: `tooling-telemetry` is `TOOLING` tier and may consume
  any production library.
* Existing `RecordingTelemetry` tests are unaffected; they don't go through the
  decorator.

## Alternatives considered

* **Build the decorator into `:runtime` itself.** Tempting, but pollutes the runtime
  with a tooling concept (the decorator pattern) and makes consent semantics part of the
  protocol's lifetime contract. The primitive (`ConsentSource`) lives in `:runtime`
  because the composition local needs to; the decorator stays in tooling.
* **Make the runtime check consent before calling `SduiTelemetry`.** Faster — saves the
  virtual dispatch — but couples the runtime to a specific consent semantics. Adopters
  who want a different gate (e.g. "telemetry off in incognito mode" but otherwise on)
  can't compose a custom decorator on top.
* **Async consent (`suspend fun isGranted`).** Adds complexity for no real win — every
  sane CMP caches the user's choice locally; there's no good reason to make the
  telemetry path go async.
