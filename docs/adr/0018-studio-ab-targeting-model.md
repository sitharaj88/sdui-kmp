# ADR-0018: Studio A/B targeting model — predicates, stickiness, hashing

Status: Accepted (M-S6)

## Context

Until M-S6 a published screen served the same tree to every client. The studio backend
(`:studio-server`) had screen authoring + versioning + audit, but no targeting layer.
Operators needed:

1. Audience targeting (e.g. only roll out to `country=US`, `appVersion≥3.0.0`).
2. A/B variants of a screen with weighted traffic split.
3. A "promote winner" path that ships the winning variant to everyone.
4. Sticky assignments so a user does not see one variant on first open and a different one
   after a refresh.
5. A way for upstream sample servers (and later, federated edge servers) to consult the
   studio without dragging in `:studio-server`'s Postgres dependency.

The constraint set: no third-party feature-flagging service, no new authentication scheme,
purely additive evolution, no clean of the existing E2E test suite. The existing M0–M5
work plus the M-S5 studio shell stays untouched.

## Decision

### Predicate language: small sealed AST, not a string DSL or JSONLogic

`AudiencePredicate` is a closed `kotlinx.serialization` sealed interface with six cases:
`Equals`, `In`, `MatchesRegex`, `And`, `Or`, `Not`. Operates on a flat
`Map<String, String>` context.

We rejected three alternatives:

* **JSONLogic.** A common choice, but its operator surface (40+ verbs incl. arithmetic,
  array operations, `var` lookups with default fallbacks, `if`/`map`/`reduce`) is way
  larger than what targeting actually needs, and it's editor-controlled — broad surface +
  editor-controlled = pathological-input attack surface (regex bombs, nested-array
  amplification).
* **A string DSL** (e.g. `"country == 'US' && tier in ('gold','platinum')"`). Forces us to
  ship a parser, a grammar, and a stable error format. Ten times the code for the same
  expressive power.
* **Inline lambdas** (a `(Map<String,String>) -> Boolean`). Violates the protocol's "actions
  are data, not code" red line — predicates need to be storeable, transportable,
  serialisable, and queryable, all of which lambdas are not.

The chosen sealed AST gives us:

* Exhaustive `when` branches in `evaluate` so adding a case is a compile error in every
  call site (mirrors the protocol's `UiNode` red line).
* Versioning via `@SerialName` discriminators, so editor-saved predicates survive a
  studio-server upgrade as long as we follow additive-only evolution.
* A trivial compile-time-bounded evaluation cost: each operator is O(1) work on a
  `Map<String,String>` plus the recursive descent the operator's children require. No
  parser, no eval loop, no JIT cost.

### Sticky assignments

Once a `(experimentId, clientId)` pair has a row in `experiment_assignments`, that row
is reused on every subsequent `assign()` call regardless of:

* Whether the experiment's variant weights changed.
* Whether the audience filter changed.
* Whether the experiment was paused and re-activated.
* Whether the chosen variant was deleted (defensive: if the variant id no longer resolves,
  we fall back to the published version and tag the response `sticky_variant_missing`).

The intent is "operators can iterate on weights without flipping live users between
variants mid-session." This matches Optimizely / LaunchDarkly defaults and is the only
configuration that makes long-running experiments interpretable. The cost is operational:
clearing assignments requires explicit DB action (no admin endpoint exposes wipe, on
purpose). For now, an operator who needs to reset reaches into Postgres directly; if the
need becomes routine we can add an admin tool later.

### Hash function: Java's `String.hashCode`

`AssignmentService.bucketFor(experimentId, clientId)` computes:

```
val key = "$experimentId|$clientId"
val unsigned = key.hashCode().toLong() and 0xFFFF_FFFFL
return (unsigned % 100).toInt()
```

The choice criteria were:

1. **Deterministic across JVM vendors and releases.** Java's `String.hashCode` is fixed in
   the JLS as `s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]` and has not changed since
   Java 1.0. Frozen by spec — the same string yields the same int on every JVM, every
   build, forever.
2. **No third-party dependency.** Surveying the toolchain at the time of the decision
   surfaced no portable Murmur3 implementation in `kotlinx-*`. The Apache Commons / Guava
   options would each add ~700 KB to the studio-server's runtime classpath and a public
   third-party API surface to track.
3. **Reasonable distribution.** `String.hashCode` is not cryptographically uniform, but
   for `(experimentId, clientId)` pairs with ≥10 chars of variation it distributes well
   enough that a 50/50 split lands within ±2pp of expected on populations of 10k+.

The trade-off is that `String.hashCode` is famously biasable for adversarial inputs
(short strings, patterns of `String.hashCode` collisions like the well-known `Aa`/`BB`
pair). For a targeting bucket the impact is "an adversary who controls clientId can
choose their own variant," which is a far smaller threat than the experiment design itself
(an attacker who knows the variant choice can already replay any client behaviour).

If a real Murmur3 ships in `kotlinx-` later, the swap criteria are: byte-identical
output across JVM/JS/Native, no new transitive deps, and a Kotlin-multiplatform-friendly
API. `AssignmentService.bucketFor` is the single replacement point.

### Sample-server delivery shape

The original RFC offered three options for where the assignment-fetch client lives:

1. New `:studio-client` Gradle module that the sample depends on.
2. Put the client side in `:studio-server` itself.
3. Inline the client in `:samples:sample-server`.

We picked **option 3**. Rationale:

* (1) is correct if multiple consumers ever materialise. Today there is exactly one
  consumer (the sample-server). Pre-emptively factoring out a module adds a tier
  classifier to `verifyDependencyRules`, a new Maven-Central artifact, and breaks the
  one-task-PR rule by mixing module-graph plumbing with a feature.
* (2) is wrong direction — the studio drags Postgres + HikariCP + Exposed. Forcing every
  upstream sample-server to inherit those is a large, lasting cost.
* (3) keeps the blast radius minimal and is straightforward to promote later. The single
  class is ~100 lines; if a second consumer shows up, lift-and-shift to a new
  `:studio-client` module is an order of magnitude smaller than building it speculatively
  now.

### Failure modes

`StudioAssignmentClient.assign()` returns `null` for every failure: env unset, network
error, non-2xx, malformed body. The sample-server treats null as "fall back to the local
default screen." Two consequences:

* Existing E2E tests that don't set `STUDIO_BASE_URL` keep passing untouched. The
  fallback path is the only path they ever take.
* A studio outage degrades the sample-server gracefully — clients see the locally-defined
  screen, not an error. This is also the only sane behaviour for a self-service tool: an
  editor's mistake in the studio (e.g. publishing a 100-weight variant pointing at a
  deleted version) must not take production traffic down.

### Schema

```
experiments(id PK, screen_id, name, description, status, created_at, updated_at, created_by)
experiment_variants(id PK, experiment_id, name, weight 0..100, screen_version_id, created_at, created_by)
audiences(id PK, name, description, predicate_json, created_at, created_by)
experiment_audiences(experiment_id PK, audience_id PK)         -- AND across rows
experiment_assignments(experiment_id PK, client_id PK, variant_id, assigned_at)
```

The audiences table stores the predicate as a kotlinx-serialization-encoded JSON string
rather than as relational rows. Trade-off: queryability (we cannot SELECT experiments by
"audiences whose predicate references country") versus simplicity (one row per audience).
Today nothing needs the structured query; if it materialises later we can mirror the
predicate into a side index without breaking the canonical column.

## Consequences

* `:protocol` and `:runtime` are not touched — clients see exactly the same wire format
  they always have. Targeting is purely a server-side decision.
* `verifyDependencyRules` already classifies `:studio-server` and `:studio-web` as
  STUDIO; no rule change is required.
* Operators can author experiments via studio-web. The new `Experiments` tab supports
  filtering, status transitions, weight visualisation, audience linking, and a text-bar
  results view (no chart library — see decline above).
* Eight studio-server tests + two sample-server tests + two studio-web boundary tests
  cover the new surface.

## Follow-ups (deferred)

* Wipe-assignments admin endpoint (currently requires DB access).
* Multiple concurrent active experiments per screen (today the most-recently-created
  one wins).
* Murmur3 hash if a kotlinx implementation lands.
* Promote-and-stop in one click (currently `promote` and "set status to completed" are
  two separate calls).
