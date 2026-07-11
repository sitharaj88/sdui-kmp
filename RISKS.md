# Risks

Known risks to the framework's 10-year lifespan, with mitigations. Reviewed at the end of each milestone.

## R1 — Protocol bloat

**Risk:** `:protocol` grows from 20 types to 200 types over five years as teams add "just one more" widget. The crown jewel stops being jewel-like.

**Likelihood:** High. Every successful framework faces this.

**Impact:** Severe. Cognitive overhead for new contributors. Review quality drops. Schema linter becomes noise.

**Mitigation:**

- Hard rule: adding a node type to `:protocol` requires a design doc.
- Before adding, ask: can this be expressed as a composition of existing nodes? Can it be a `NativeSurface`?
- Annual audit at the end of each year: deprecate anything emitted less than 1% of the time based on telemetry.
- Public-type count is a tracked metric. A sudden jump triggers review.

## R2 — The escape hatch becomes the main entrance

**Risk:** `NativeSurface` is so flexible that teams stop building widgets and stop using protocol-level primitives. Protocol becomes vestigial.

**Likelihood:** Medium-high. Happens to every extension point.

**Impact:** Severe. Defeats the entire purpose of SDUI.

**Mitigation:**

- `NativeSurface` usage is telemetry-tracked per `kind`.
- If a `kind` appears in more than 5% of trees, a design review is mandated to either promote it to a protocol primitive or cap it.
- The design doc for any new `NativeSurface` kind must explain why a protocol primitive won't work.

## R3 — Schema drift via "just a tiny string change"

**Risk:** Someone renames a `@SerialName` to match a new convention. Old clients stop decoding.

**Likelihood:** Medium. It happens even with linters unless they fail loudly.

**Impact:** Catastrophic. Breaks every old client in production.

**Mitigation:**

- Schema linter fails CI on any `@SerialName` change.
- Snapshot is regenerated only at release tags, never mid-development.
- Rollback playbook documented at `docs/operations/SCHEMA_ROLLBACK.md` (written before M9).

## R4 — Compose Multiplatform instability

**Risk:** Compose MP changes its rendering semantics in a major version, breaking our renderers.

**Likelihood:** Low-medium. JetBrains has been careful but not perfect.

**Impact:** Moderate. A compatibility layer would be needed.

**Mitigation:**

- Pin Compose MP versions tightly. Upgrade in scheduled sprints, never opportunistically.
- Golden snapshot tests catch rendering changes before release.
- An abstraction layer between widgets and Compose primitives is out of scope — we accept the coupling and mitigate via tests.

## R5 — Wasm bundle size

**Risk:** Wasm output exceeds acceptable size for web deployment. Adoption on web stalls.

**Likelihood:** Medium. Kotlin/Wasm is still evolving.

**Impact:** Moderate. Would limit web target usefulness but not kill the framework.

**Mitigation:**

- Track Wasm bundle size in CI from M2 onward.
- Budget: under 1 MB gzipped for the minimum viable renderer through M5.
- If we exceed the budget, prioritize tree-shaking and lazy widget registration.

## R6 — Performance on large trees

**Risk:** Screens with 1,000+ nodes or lists with 10,000+ items drop frames.

**Likelihood:** High if we don't measure early.

**Impact:** Severe for adoption in specific categories (dashboards, finance, feeds).

**Mitigation:**

- Benchmark suite added in M8, but running on sample screens from M1.
- `LazyList` designed from day one to never materialize off-screen templates.
- Protobuf transport available as opt-in from M8.
- Performance regression in CI: p99 screen render time must not exceed the previous milestone's p99 by more than 5%.

## R7 — Offline action queue corruption

**Risk:** Optimistic updates plus offline queue plus retries plus idempotency keys — many moving parts. Bugs here are data-loss severe.

**Likelihood:** Medium. This is a hard problem.

**Impact:** Severe. User trust lost.

**Mitigation:**

- Offline queue is a dedicated sub-module with property-based tests.
- Every action has an idempotency key (server-enforced).
- Conflict resolution is server-driven (the server returns an `ActionResult.Conflict` with UI to resolve).
- Action log is persisted atomically — no partial writes.

## R8 — Ecosystem bus factor

**Risk:** Framework becomes dependent on one or two maintainers. They leave. Framework decays.

**Likelihood:** Medium over a 10-year horizon.

**Impact:** Severe.

**Mitigation:**

- Every document in this package is written for a reader who has never met the authors.
- Architecture decisions are captured in ADRs (added in M7).
- No single person owns `:protocol` — PRs require two reviewers.
- Tooling is as polished as the framework itself. A new team can run it without tribal knowledge.

## R9 — Accessibility as an afterthought

**Risk:** Accessibility is on every widget from M1, but renderers don't actually wire it to platform a11y services. Ship with false compliance.

**Likelihood:** Medium if we don't audit.

**Impact:** Severe. Legal and ethical.

**Mitigation:**

- M8 includes a dedicated accessibility audit.
- Every widget's golden tests include a screen-reader output verification.
- Every renderer must explain how `A11y` fields map to platform services.

## R10 — Complexity creep in the DSL

**Risk:** Server DSL grows to include loops, conditionals, helpers, shared state. Becomes a parallel framework.

**Likelihood:** Medium. The temptation is real.

**Impact:** Moderate. Less severe than protocol bloat, but still worth avoiding.

**Mitigation:**

- The DSL is a thin builder over `:protocol` types. It does not introduce concepts.
- If server-side logic is needed, it runs in normal Kotlin before the DSL. The DSL receives finished values.
- Review guidance in `docs/CONVENTIONS.md`: reject DSL additions that are not 1:1 with a protocol type.

## Reviewing this list

At the end of each milestone:

1. Re-read every risk.
2. Update likelihood and impact based on new information.
3. Add any risks discovered during the milestone.
4. Close risks that the milestone's work has eliminated.
5. If a risk's likelihood × impact increased, escalate.
