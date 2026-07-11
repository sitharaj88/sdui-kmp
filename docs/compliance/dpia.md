# DPIA template — adopters integrating sdui-kmp

This is a template a controller can use to scaffold a Data Protection Impact Assessment
when adding sdui-kmp to a product. It is **not legal advice**. Have your DPO or external
counsel review the completed assessment before publishing.

A DPIA is required under GDPR Article 35 when processing is "likely to result in a high
risk to the rights and freedoms of natural persons." Server-driven UIs themselves are
*not* high-risk; what's processed *through* them often is. Run a DPIA when sdui-kmp is
the surface for any of: large-scale profiling, special-category data (health, biometrics,
political views), systematic monitoring, automated decision-making.

---

## 1. Description of the processing

**Product**: <name of the adopter's product>

**Purpose of using sdui-kmp**: server-driven UI rendering for <list the channels:
mobile app, desktop, web>.

**Data flowing through sdui-kmp screens**:

| Field | Category | Origin | Sensitivity | Retention |
|---|---|---|---|---|
| <e.g. customer email> | Identifier | adopter's IAM | Personal | <see retention table> |
| <e.g. account balance> | Financial | adopter's billing system | Personal | session-only |
| <e.g. health records> | Special-category | adopter's clinical store | Sensitive | encrypted at rest, 7-day cache TTL |

**Categories of data subjects**: <e.g. customers, employees, support agents, ...>

**Recipients**: <enumerate every party that receives the data: adopter's own backend,
the configured `SduiTelemetry` sub-processor, the `transport-cache` on-device store,
the studio audit log...>

## 2. Necessity and proportionality

* **Lawful basis** (Art. 6): <Contract / Legitimate interest / Consent / ...>
* **Special-category basis** (Art. 9): <if applicable>
* **Why server-driven UI?** <2-3 sentences justifying the architectural choice. The
  framework's rationale — fast iteration, multi-platform parity — does not by itself
  justify processing personal data; document why the alternative (compile-time UI) is
  inadequate for *this* product.>

## 3. Risks identified

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Server emits a `Value.Literal` containing PII that ends up in `transport-cache` longer than necessary | Medium | Medium | Use `Value.Bind` and `Value.Template` per [data-minimization.md](data-minimization.md). Set `transport-cache` TTL to <X minutes>. |
| Telemetry adapter exfiltrates payload values | Low | High | Only the `OpenTelemetryTelemetry` adapter ships counts and types. Custom adapters must be reviewed before deployment. Wire all telemetry through `ConsentAwareTelemetry`. |
| Studio editor saves a screen with embedded PII; the audit log retains it | Low | Medium | Keep `studioAudit.captureFullDiff = false` (default). Editor user IDs only. |
| Action queue persists PII at rest in transit | Medium | Medium | Encrypt the offline-queue store. Action payloads should be `StatePath` references, never `Value.Literal` for sensitive fields. |
| Screen-reader announcements leak sensitive values to bystanders | Low | Low/Medium | Document in the user-facing privacy notice. Consider whether `liveRegion = Polite` is appropriate for sensitive content. |

## 4. Measures

| Mitigation | Where it lives in the framework |
|---|---|
| Consent-aware telemetry | [`ConsentAwareTelemetry`](../../tooling-telemetry/src/commonMain/kotlin/dev/sdui/kmp/tooling/telemetry/ConsentAwareTelemetry.kt) |
| Server-side rate-limit & idempotency | `:auth-rs256` rate-limit + `submit_idempotency` table in the sample server |
| At-rest encryption of state-store snapshots | adopter's responsibility; the framework keeps state in memory only |
| TTL on the on-device cache | adopter configures `ScreenDiskCache` retention |
| Audit log of editor changes | `studio_audit` table (default retention: 365 days) |

## 5. Consultation

| Stakeholder | Date | Notes |
|---|---|---|
| DPO | | |
| Engineering | | |
| Security | | |
| External counsel | | |

## 6. Outcome

* [ ] Risk-reducing measures sufficient — proceed.
* [ ] Residual risk acceptable — proceed with monitoring.
* [ ] Residual risk too high — consult supervisory authority before processing.

---

## Appendix — sdui-kmp-specific considerations

### What the framework guarantees out of the box

* No phone-home telemetry. The framework's default `SduiTelemetry` is `NoopTelemetry`.
* No automatic crash reporting. Adopters wire that through `ConsentScope.Crash`.
* Actions encode as serializable data, not closures, so they're auditable and
  replayable without dragging captured environments along.
* The `:protocol` module has zero network or persistence dependencies.

### What the framework deliberately does NOT provide

* A CMP (Consent Management Platform). Adopters wire their own through
  [`LocalConsentSource`](../../runtime/src/commonMain/kotlin/dev/sdui/kmp/runtime/Consent.kt).
* Encryption at rest of the action queue, the StateStore snapshots, or the offline
  cache. Adopters add this with their platform's keystore + cipher of choice.
* Data Subject Access Request export tooling. The framework does not warehouse user
  data, so there's nothing for it to export.
* PII detection in the schema linter. A server engineer who hard-codes an email into
  `Value.Literal` will not be caught at build time. Code review and the principles in
  [data-minimization.md](data-minimization.md) are the controls.
