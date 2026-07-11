# ADR-0013: Kover coverage floor

* Status: Accepted
* Date: 2026-04-25
* Deciders: framework engineering
* Supersedes / superseded by: —

## Context

Until this change, the framework had ~270+ tests across `:protocol`, `:runtime`, the widget
tier, the transport tier, and the tooling tier — but no coverage measurement. We had no
mechanical signal when a refactor that gutted a code path slipped through review, and no
way to point at "this module is undertested" without grepping the test tree.

Five-to-ten-year frameworks need that signal. Coverage is not a quality metric on its own
(and we are not chasing 100%), but a trend line on the merged report is the cheapest
available regression detector for the kind of change that adds public API and forgets to
exercise it.

## Decision

1. Apply `org.jetbrains.kotlinx.kover` 0.9.x to every library module via the
   `sdui.coverage` convention plugin (transitively pulled in by `sdui.kmp.library` and
   `sdui.jvm.library`). No module needs to opt in by hand.
2. The root `build.gradle.kts` aggregates per-module reports via `dependencies { kover(project(...)) }`
   and runs the single verify rule that gates `check`.
3. Modules excluded from the aggregated report:
   * `:samples:*` — sample apps are not framework code.
   * `:studio-server`, `:studio-web` — deployable apps, not shipping libraries.
   * `:benchmarks` — JMH harness, no behavior to cover.
   * `:tooling-preview` — IDE-only entry point.
4. **Coverage floor: 60% line coverage on the merged report.**
5. The verify rule is wired into `:tooling-cli:check` via a root-level `coverageGate`
   umbrella task, mirroring how `verifyDependencyRules` and `verifyProtocolSnapshot` are
   already wired.
6. CI uploads the aggregated `koverXmlReport` as an artifact; Codecov upload is
   intentionally **not** added in this change so the artifact is available for review
   without committing to a downstream service.

## Reasoning for the floor

Per-module baselines measured at the time of this change (line counter, JVM target):

| Module                  | Lines covered / total | %      |
|-------------------------|-----------------------|--------|
| `:protocol`             | 302 / 308             | ~98%   |
| `:runtime`              | 172 / 352             | ~49%   |
| **Aggregated (JVM)**    | **1548 / 2386**       | **~65%** |

The aggregated 65% is dragged down by:

* `:runtime` is largely Compose `@Composable` code; Compose-emitted bytecode is
  notoriously hard to reach with non-instrumented unit tests, and Compose UI tests
  aren't yet wired into the JVM pipeline.
* `:widgets-*` and `:transport-*` modules ship `@Composable` renderers and platform-
  specific clients respectively; the same constraint applies.

Setting the floor at **60%** leaves a ~5pp regression budget — enough headroom that a
routine refactor doesn't immediately turn the build red, but tight enough that a "delete
half the tests" PR will fail the gate. A higher floor would require either:

  * Compose UI tests on the JVM (Robolectric or `compose-ui-test`) — out of scope for
    this task; we'll tighten the floor when those land in a separate PR.
  * Carving the widget tier out of the merged measurement — that hides the true coverage
    trend, which is what we want to track.

A lower floor (50% or below) makes the gate vacuous against the current baseline.

## Consequences

* New modules ship with their tests measured automatically; no per-module Kover wiring.
* The single floor is the only knob. Tightening it is a one-line PR review.
* Compose-heavy modules pull the aggregate down; that's a known-and-accepted ceiling
  until the UI test track lands.
* CI's aggregated `koverXmlReport` step takes ~5 extra minutes on the Linux job. That's
  acceptable for the regression detection it buys.

## Open follow-ups

* Wire Compose UI tests so the runtime / widgets coverage can be measured properly.
* When the floor crosses 70%, raise it; when it crosses 80%, raise it again.
* Decide whether to add Codecov (or alternative) as a downstream consumer of the XML
  artifact. Today the artifact is uploaded but not consumed.
