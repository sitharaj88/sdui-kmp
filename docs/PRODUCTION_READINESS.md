# sdui-kmp — Production-Readiness & Scalability Audit

## Remediation status (updated 2026-07-11)

All confirmed **critical/high** blockers from this audit have been fixed across three reviewed
waves (each adversarially reviewed and verified green: 841 tests, 0 failures, `verifyDependencyRules`
clean, `verifyProtocolSnapshot` clean, `:studio-server:detekt` clean).

| # | Blocker | Status |
|---|---------|--------|
| 1 | Forgeable admin tokens (public fallback JWT secret) | ✅ fail-fast unless `SDUI_ENV=dev` (`eaf054d`) |
| 2 | Unknown non-`UiNode` types blank the screen | ✅ `Unknown` sentinels for all 12 wire sealed types (`7dab198`) |
| 3 | Unknown enum cases throw | ✅ `coerceInputValues` + neutral enum defaults (`7dab198`) |
| 4 | Cross-JVM live fan-out never wired | ✅ publish via `LiveBus` + dynamic bridging + A→B test (`004c10b`) |
| 5 | Unbounded render recursion → StackOverflow | ✅ depth/node budget guard (`7dab198`) |
| 6 | Unauth `/assign` ReDoS + unbounded writes | ✅ service token + rate-limit + compile-at-create + idempotent (`18a54f9`) |
| 7 | Clients never reconnect | ✅ capped backoff + jitter + resync hook (`004c10b`) |
| 8 | Broadcast head-of-line blocking | ✅ concurrent, per-send timeout, dead-peer reap (`004c10b`) |
| 9 | No login rate-limit | ✅ IP+email progressive lockout + per-IP plugin (`18a54f9`) |
| 10 | CSRF/rate-limit plugins don't short-circuit | ✅ `ShortCircuitPhase` halts the pipeline (`18a54f9`) |
| 11 | Release publishes with no test/schema gate | ✅ gates run before Sonatype close/release (`b726373`) |
| 12 | studio-server under-operable + silent H2 wipe | ✅ prelude + graceful shutdown + H2 fail-fast (`22f3ac5`) |
| — | `listScreens` full-history heap scan | ✅ projects (id, version) (`004c10b`) |
| — | No StatusPages error contract | ✅ 400/404/409/500 uniform envelope (`22f3ac5`) |
| — | Flyway set missing tables / no runner | ✅ full migration set + Dockerfile/compose runner (`c05bb3c`) |

**Remaining follow-ups** (lower-severity hardening, tracked — not go-live blockers):
- Reconnect: don't reset backoff on an accept-then-close flap; fire resync after a *successful* connect.
- Live bus: sharded/per-topic Postgres channels + a dropped-event metric for hot-topic scale.
- Integration test for the graceful-shutdown ordering; strengthen `MigrationSchemaParityTest` to compare column types/nullability.
- Guardrail debt (original audit Phase 4): run Detekt project-wide in CI, re-enable studio-web's disabled tests, golden snapshots for `widgets-core`, per-module coverage floor.
- Address the Gradle-9 deprecation warnings before that upgrade.

The verdict below reflects the **original audit** (pre-remediation), retained for the record.

---
> Generated 2026-07-11 by a 7-dimension expert audit (33 agents, serious findings adversarially verified). Core JVM test suites pass. This document is the durable record of the executive synthesis plus every confirmed finding with file:line and fix.
---
# Production-Readiness & Scalability Verdict: sdui-kmp

## 1. Overall Verdict: **NOT READY**

The framework's *design* is genuinely strong — one sealed protocol shared by both sides, an additive-only linter that runs against real generated descriptors, deterministic server rendering, and a real observability stack. But it ships two **confirmed critical** defects and a **non-functional horizontal-scaling core**, any one of which is a hard stop for production. The control plane (studio-server) can be authenticated with a hard-coded HMAC secret that is published in the open-source repo (`StudioJwt.kt:59`), so a deployment that forgets one env var is silently forgeable across the entire admin/publish/RBAC surface. Separately, the framework's central "additive evolution, old clients never break" premise is only half-implemented: only `UiNode` degrades gracefully on unknown discriminators — every other sealed hierarchy and every enum growth hard-fails the *entire screen* on older clients (`SduiJson.kt:25`, `:37`). And the cross-process live-update path that the whole scaling story rests on is never wired (`LiveBus.publish()` has zero production callers). These are not polish items; they defeat stated non-negotiables. Fixable in weeks, but not shippable today.

## 2. Scalability Verdict: **Cannot scale horizontally today**

The live/transport layer has good coroutine and cache hygiene (per-topic mutex, non-blocking `DROP_OLDEST`, atomic disk writes), but the horizontal-scaling core is inoperative. Hard blockers, in order:

| Blocker | Location | Fix |
|---|---|---|
| **Cross-JVM fan-out is never triggered.** `WebSocketPublishNotifier` broadcasts only to the *local* WS publisher; nothing ever calls `LiveBus.publish()`, so no `NOTIFY sdui_live` is emitted. A screen published on JVM A never reaches clients on JVM B. Multi-instance live updates do not work at all. | `WebSocketPublishNotifier.kt:33` | Route the publish path through `LiveBus.publish(topic, event)`; add a two-module integration test asserting cross-JVM delivery. |
| **Clients never reconnect.** `runSession()` opens one socket, loops once, completes. `start()`'s `if (pump != null) return` guard then blocks any restart. Every rolling deploy / LB idle-timeout / blip permanently silences live updates until process teardown. | `WebSocketLiveSource.kt:74` | Wrap in a capped-backoff + jitter reconnect loop; on reconnect force an HTTP re-fetch to close the no-replay gap. |
| **Broadcast head-of-line blocking.** Fan-out `send()`s sequentially with no timeout; one slow/stalled peer stalls delivery to every later subscriber on a hot topic. | `WebSocketLivePublisher.kt:73` | Fan out concurrently with a per-send `withTimeout`; bounded drop-oldest per-session outbound queue. |
| **Admin listing is an O(all history × body size) heap scan.** `listScreens` does `ScreenVersions.selectAll()` materializing every `body_json` TEXT of every version ever, just to map a UUID to a version number. Degrades toward OOM/pool-stall over a 5–10-year history. | `ScreenStore.kt:59` | Project only `(id, versionNumber)`, or resolve version number via a join keyed on `current_version_id`. |
| **Unbounded topic registry.** `unregister()` never removes empty `TopicState`; topic key comes from the URL path, so any client mints unlimited permanently-retained entries. | `WebSocketLivePublisher.kt:54` | Remove the key when its set empties (guard against concurrent register); optionally allowlist `{id}`. |
| **Single Postgres channel + 64-slot shared buffer** multiplexes all topics; a burst on one hot topic silently evicts unrelated events, and every JVM decodes every event. | `PostgresLivePublisher.kt:96` | Sharded/per-topic channels or an external broker (Redis/NATS); larger/per-topic buffer + dropped-event metric. |

Until the first three are fixed, the answer to "can it scale horizontally?" is **no** — it degrades to correct single-process behavior only.

## 3. Top Blockers (must fix before prod, ranked)

1. **Forgeable admin tokens via hard-coded fallback secret** — `studio-server/.../auth/StudioJwt.kt:59`. `fromEnv()` silently falls back to public constant `FALLBACK_SECRET` with symmetric HMAC256; anyone can mint admin-role JWTs. **Fix:** fail-fast at boot when `STUDIO_JWT_SECRET` is absent outside an explicit `SDUI_ENV=dev`; move to the already-built `:auth-rs256` asymmetric issuer so no signing key ships in source.
2. **Unknown non-`UiNode` discriminators blank the whole screen** — `protocol/.../SduiJson.kt:25`. Only `UiNode` has a `defaultDeserializer`; a new `Action`/`ColorToken`/`Value`/etc. subtype anywhere in the tree throws, and `HttpScreenSource.kt:105` turns it into a full-screen `Error`. Adding `Action.Share` bricks every deployed older client — the linter green-lights it. **Fix:** register polymorphic default fallbacks (`Unknown*` sentinels, treated as inert) for every wire-crossing sealed hierarchy; one property test per hierarchy proving no-throw.
3. **Unknown enum cases throw** — `SduiJson.kt:37`. No `coerceInputValues`; adding a `Spacing`/`ButtonStyle`/etc. case (explicitly blessed by non-negotiable #2) is a fleet-wide screen outage on older clients. **Fix:** `coerceInputValues=true` **plus** a default on every wire-facing enum field (coercion only fires with a default); regression-test a bogus enum value decodes without throwing.
4. **Cross-JVM live fan-out never wired** — `WebSocketPublishNotifier.kt:33` (see §2).
5. **Unbounded rendering recursion → StackOverflowError crash** — `runtime/.../RenderNode.kt:26`, also `TreePatchEngine.kt:41-76`. No depth/node budget at decode or composition; a deep `Column`-in-`Column` tree from an untrusted server hard-crashes the client, violating "never crash." **Fix:** enforce a max depth/node budget at decode (`SduiJson`) or via a depth-tracking `CompositionLocal`; render fallback + telemetry beyond the cap.
6. **Unauthenticated `/screens/{id}/assign` is a stored-ReDoS + unbounded-write vector** — `AudiencePredicate.kt:72`, mounted outside `authenticate{}` at `ExperimentRoutes.kt:238`. Create-time validation runs `evaluate(emptyMap())`, which short-circuits before the regex compiles, so catastrophic patterns are stored and then recompiled per anonymous request; the GET also writes one `experiment_assignments` row per distinct client id with no rate limit. **Fix:** compile + reject patterns at create time and cache compiled `Regex`; put the endpoint behind a service token + rate limit; stop writing on GET.
7. **Clients never reconnect** — `WebSocketLiveSource.kt:74` (see §2).
8. **Broadcast head-of-line blocking** — `WebSocketLivePublisher.kt:73` (see §2).
9. **No login rate-limiting on the admin backend** — `StudioModule.kt:79`. Unauthenticated `POST /admin/auth/login` has no throttle/lockout; unlimited credential-stuffing against bcrypt hashes, and each attempt is a ~250ms CPU DoS amplifier. **Fix:** install `RateLimitPlugin` (keyed on IP + email) with progressive lockout; use a shared store for multi-replica.
10. **CSRF / rate-limit plugins don't short-circuit the pipeline** — `auth-rs256/.../CsrfPlugin.kt:118`, `RateLimitPlugin.kt:99`. In Ktor 3.x, responding in `onCall` without `finish()` lets the matched handler still run, so login side effects (session write, token mint) execute on a request the client sees rejected. **Fix:** explicitly short-circuit after responding; add a test asserting the guarded handler's side effect does **not** occur.
11. **Release publishes to Maven Central with no test/schema gate** — `.github/workflows/release.yml:52`. Any tag on any commit runs only POM pre-flight then `closeAndReleaseSonatypeStagingRepository` (irreversible). A protocol break or red test on the tagged SHA ships to Central. **Fix:** add `verifyProtocolSnapshot verifyDependencyRules check` before publish, or `needs:` a green CI run pinned to the tagged SHA.
12. **studio-server control plane is materially less operable than the sample** — `StudioModule.kt:89`. No readiness probe, no request-id correlation, no access log, and a silent in-memory H2 fallback (`StudioDatabase.kt:92`) that wipes drafts/versions/RBAC/**audit log** on every restart. **Fix:** mirror sample-server's production prelude; make the H2 fallback fail-fast outside dev.

## 4. Dimension Scorecard

| Dimension | Grade | One-line |
|---|---|---|
| Protocol | C | Excellent modeling + additive linter, but forward-compat runtime is half-built: only `UiNode` degrades; new Action/token subtypes and enum cases hard-fail old clients. |
| Runtime | C | Unknown-node dispatch and resolvers are defensive, but no renderer error boundary, no recursion cap (StackOverflow crash), and the flagship no-crash invariant has no end-to-end test. |
| Server-render | C | Sound deterministic DSL and clean store layering, undercut by a full-table-scan listing, missing migrations, no error contract, and a ReDoS-exposed unauth assign path. |
| Scalability | D | Good coroutine/cache hygiene, but the horizontal-scaling core is non-functional: publish never wired, clients never reconnect, blocking fan-out, unbounded registry. |
| Security | C | Correct JWT/RBAC/bcrypt primitives, wrecked by a published fallback signing secret, no login rate limit, and plugins that don't short-circuit. |
| Build-CI | C | Strong multi-OS matrix + real schema/dependency gates, but Detekt runs for 1 of 30 modules, a module's 38 tests are disabled, and releases publish with no test/schema gate. |
| Ops | C | Genuinely strong observability (Prometheus/OTel/Sentry/readiness/runbooks), undercut by insecure secret fallbacks, no graceful shutdown, and an under-instrumented control plane. |

## 5. Genuine Strengths (already production-grade)

- **Protocol modeling and the additive linter.** The snapshot is derived from real generated `KSerializer` descriptors (not a shadow schema), the schema-snapshot job is a blocking `needs:` prerequisite gating the whole matrix, and it catches removals, `@SerialName` renames, tightened nullability, optional→required, and changed discriminators. Actions are data (no lambdas); semantic tokens are enforced by construction.
- **Unknown-`UiNode` fallback** is well-engineered and property-tested (`UnknownNodeFallbackPropertyTest` covers arbitrary field soup, nested fallback, explicit null).
- **Deterministic server rendering** — path-based `NodeIdAllocator` yields byte-identical trees across runs; drafts re-encode through `Screen.serializer()` for normalized bodies; publish/revert are append-only under a unique index.
- **Correct security primitives** — RS256 verifier pins the algorithm (no alg-confusion), RBAC checks permission + session liveness on every admin route, bcrypt cost 12, no response-body enumeration.
- **Observability** — Micrometer request timer wrapping every handler, JVM binders, structured JSON logs with request-id MDC, layered liveness/readiness, OTel + Sentry adapters, SLO/runbook docs, container-aware heap sizing.
- **Runtime resilience where it exists** — overflow-safe retry backoff, optimistic-update snapshot/rollback, stale-patch no-ops, per-item state scoping, version-window dispatch.
- **Build hygiene** — fully pinned version catalog (no `+`), `explicitApi()` in both conventions, POM pre-flight before publish, consumer R8 rules shipped in every AAR.

## 6. Recommended Path to Production (ordered punch-list)

**Phase 1 — Security & correctness stop-ships (block all releases):**
1. Fail-fast on missing `STUDIO_JWT_SECRET`; migrate studio-server to the `:auth-rs256` asymmetric issuer (`StudioJwt.kt:59`).
2. Register polymorphic `Unknown*` fallbacks for every wire-crossing sealed hierarchy + property tests (`SduiJson.kt:25`).
3. Enable `coerceInputValues` and add defaults to all wire-facing enum fields (`SduiJson.kt:37`).
4. Add a max tree depth/node budget at decode and in `TreePatchEngine`; cap composition recursion (`RenderNode.kt:26`).
5. Gate `/screens/{id}/assign` behind a service token + rate limit; compile+reject regex at create time and cache it; stop writing on GET (`ExperimentRoutes.kt:238`, `AudiencePredicate.kt:72`).
6. Install `RateLimitPlugin` on `/admin/auth/login`; fix CSRF/rate-limit short-circuit + side-effect test (`StudioModule.kt:79`, `CsrfPlugin.kt:118`).

**Phase 2 — Make horizontal scaling actually work:**
7. Wire the studio publish path through `LiveBus.publish()`; add a two-JVM integration test (`WebSocketPublishNotifier.kt:33`).
8. Add reconnect-with-backoff + HTTP resync to `WebSocketLiveSource` (`:74`).
9. Concurrent, timeout-bounded broadcast fan-out (`WebSocketLivePublisher.kt:73`); evict empty topic keys (`:54`).
10. Fix `listScreens` to project two columns / join (`ScreenStore.kt:59`).
11. Dynamic topic bridging from the register callback instead of the hardcoded list (`Main.kt:265`).

**Phase 3 — Operability & release engineering:**
12. Mirror sample-server's production prelude into studio-server (readiness, request-id, access log); make the H2 fallback fail-fast (`StudioModule.kt:89`, `StudioDatabase.kt:92`).
13. Add `verifyProtocolSnapshot verifyDependencyRules check` (or `needs:` a green pinned CI run) to `release.yml`.
14. Install `StatusPages` mapping `IllegalState/ExposedSQLException` → 4xx/409; convert store races to `insertIgnore`/upsert (`ScreenStore.kt`, `ExperimentStore.kt:303`).
15. Add a CI step that re-captures the snapshot and `git diff --exit-code`s it; replace the tautological `LintTest.kt:202`.
16. Write the real Flyway V2+ migrations for the 9 missing tables and disable runtime auto-DDL in prod; graceful shutdown (`ApplicationStopping` → cancel `liveBridgeScope`, drain WS, close Hikari).

**Phase 4 — Test & guardrail debt (before scale-out, not before first prod):**
17. End-to-end Compose tests for `RenderNode` (unknown node, out-of-range version, throwing renderer, deep tree).
18. Run Detekt project-wide in CI; re-enable studio-web's 38 tests (move to JVM target); add golden snapshots for `widgets-core`.
19. Per-module coverage floor; apply kotlinx binary-compatibility-validator to shipping library modules; checksum-pin the Gradle wrapper.

Note: the ops "rate-limiter bucket-flush" finding was **refuted** on verification (no forwarded-headers plugin is installed, so `X-Forwarded-For` is not client-controllable) — treat it as a low, documented single-instance limitation, not a blocker. The distinct login-rate-limit gap (#9) is real and separate.

---

# Appendix: Full findings by dimension

## Protocol integrity & schema evolution — grade C
*Protocol modeling and the additive linter are strong, but the forward-compatibility runtime story is only half-built — only UiNode degrades gracefully, while unknown Action/token subtypes and new enum cases hard-fail the entire screen on older clients, and CI never verifies the snapshot baseline is current.*

### 🔴 CRITICAL — Unknown discriminator degrades gracefully ONLY for UiNode; every other sealed hierarchy hard-fails the whole screen · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `protocol/src/commonMain/kotlin/dev/sdui/kmp/protocol/SduiJson.kt:25`
- **Problem:** SduiSerializersModule registers a polymorphic defaultDeserializer for UiNode ONLY (SduiJson.kt:25-29), producing UnknownUiNode on unrecognized `type`. But Action (Action.kt:15), ColorToken (DesignTokens.kt:10, sealed — not an enum), IconToken (DesignTokens.kt:41), Value, Predicate, ListSource, Validation, LiveEvent, PatchOp, RetryPolicy, and Destination are all sealed-polymorphic with NO default deserializer. When an additively-evolved server sends a new subtype of any of these to an older client, kotlinx throws SerializationException. Because HttpScreenSource decodes the entire tree in one shot (HttpScreenSource.kt:90) and catches at :105, a single unrecognized Action/ColorToken/etc. anywhere in the tree blanks the ENTIRE screen into ScreenState.Error. This directly defeats the framework's central premise (additive-only evolution with old clients staying in the field for 5-10 years) and the spirit of non-negotiable #3. The additive linter will happily green-light adding `Action.Share` or `ColorToken.Tertiary`, and that addition silently bricks screens on every deployed older client.
- **Fix:** Register polymorphic defaultDeserializer fallbacks for every sealed hierarchy that crosses the wire (Action -> a no-op UnknownAction, ColorToken -> a neutral default token, etc.), mirroring the UiNode pattern. At minimum add an `Unknown` sentinel subtype per hierarchy and document that renderers/action-dispatchers must treat it as inert. Add a property test per hierarchy proving unknown discriminators never throw.

### 🟠 HIGH — Unknown enum cases throw — additive enum growth breaks old clients (no coerceInputValues) · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `protocol/src/commonMain/kotlin/dev/sdui/kmp/protocol/SduiJson.kt:37`
- **Problem:** SduiJson (SduiJson.kt:37-43) sets classDiscriminator, ignoreUnknownKeys, explicitNulls, encodeDefaults — but NOT coerceInputValues. The protocol's own rules explicitly permit adding enum cases ('Enum cases added, never repurposed'). Spacing, TextStyleToken, RadiusToken, ElevationToken, NavKind, ButtonStyle, HttpMethod, ContentScale, Keyboard, StateScope, A11yRole, LiveRegion, Orientation are plain enums. When a newer server emits a newly-added case (e.g. Spacing.Xxxl) to an older client, decode throws SerializationException, and via HttpScreenSource.kt:90/105 the whole screen fails. So the one evolution move the docs most explicitly bless — adding an enum case — is a fleet-wide screen outage for older clients. grep confirms coerceInputValues appears nowhere in the codebase.
- **Fix:** Enable coerceInputValues=true in SduiJson AND give every wire-facing enum field a default value so unknown cases coerce to a safe default instead of throwing. Add a regression test decoding a JSON payload with a bogus enum value and asserting no throw.

### 🟠 HIGH — CI never verifies the committed protocol-snapshot.json is up-to-date; additive changes silently escape protection _(verified: CONFIRMED)_
- **Where:** `build.gradle.kts:91`
- **Problem:** verifyProtocolSnapshot runs `lint` (Main.kt:42), which only reports BREAKING changes (removals, type/nullability/discriminator changes) of current vs the committed file. It passes when the committed snapshot is a stale SUBSET missing additive changes. Nothing runs `captureProtocolSnapshot` and asserts `git diff --exit-code protocol-snapshot.json` — the capture task (build.gradle.kts:76) is manual-only and is not in ci.yml's schema-snapshot job. The one test that looks like a freshness check, LintTest.kt:202, compares captureProtocolSnapshot() to ITSELF (a tautology, always empty), not to the committed file. Consequence: a developer who adds a field/type/subtype but forgets to re-run capture leaves it absent from the baseline; it is then permanently unprotected, and its later removal or type-change produces zero violations. Over a 5-10 year additive protocol this steadily erodes the guarantee the linter is supposed to provide.
- **Fix:** Add a CI step (and a Gradle verify task) that runs captureProtocolSnapshot to a temp file and fails if it differs from the committed protocol-snapshot.json (or `git diff --exit-code` after capture). Replace the tautological LintTest.kt:202 with one that lints the committed file against a fresh capture.

### 🟡 MEDIUM — Linter cannot detect element/value-type changes inside List/Map fields — a red-line 'changed type' escape
- **Where:** `tooling-cli/src/main/kotlin/dev/sdui/kmp/tooling/cli/SnapshotCapture.kt:145`
- **Problem:** toDataClassShape records FieldShape.type = elementDesc.serialName (SnapshotCapture.kt:145). For collection fields the serialName is the container only: the snapshot shows `actions` as `kotlin.collections.ArrayList` and map fields as `kotlin.collections.LinkedHashMap` (verified in protocol-snapshot.json around the Action.sequence/submit entries). The element and map key/value types are erased. Therefore changing `List<Action>` -> `List<UiNode>`, `Map<String,StatePath>` -> `Map<String,String>`, or `List<Action>` -> `List<Action?>` is NOT flagged by ChangedFieldType — a direct escape from the CLAUDE.md red line 'never change the type of an existing field.' The linter's field-type check (Lint.kt:103) only compares these coarse container names.
- **Fix:** When capturing a collection/map field, fold the element (and map key/value) serialName and nullability into the recorded type string (e.g. `kotlin.collections.List<dev.sdui.kmp.protocol.Action>`), so element-type changes surface as ChangedFieldType.

### 🟡 MEDIUM — Snapshot type registry is hand-maintained; unregistered @Serializable types are invisible to the linter
- **Where:** `tooling-cli/src/main/kotlin/dev/sdui/kmp/tooling/cli/SnapshotCapture.kt:68`
- **Problem:** captureProtocolSnapshot enumerates protocol types via three hand-written lists (SnapshotCapture.kt:68-124); the KDoc at :52-59 explicitly acknowledges 'every new @Serializable type added to :protocol must be added to the corresponding list or its shape won't be covered by the linter' and defers enforcement to a hypothetical future tool. A type a developer forgets to register is never snapshotted, so its fields/subtypes are never protected and can be removed or retyped later with zero violations. For a protocol meant to be the single source of truth for a decade, the completeness of its own guard rests on manual discipline.
- **Fix:** Add an enforcement step that reflects/scans all @Serializable declarations in :protocol (e.g. via a compiler-plugin-generated registry or a source scan) and fails if any is missing from the capture lists, closing the manual-omission gap the code comment describes.

### ⚪ LOW — Value<T> literals are type-erased on the wire with no server-side type check
- **Where:** `protocol/src/commonMain/kotlin/dev/sdui/kmp/protocol/Value.kt:29`
- **Problem:** Value.Literal<T> stores a raw JsonElement (Value.kt:29) with T as a compile-time phantom (documented, ADR 0001). This is a sound serialization workaround, but it means a server can emit `Value.ofString(...)` into a field typed `Value<Int>` and nothing — not the type system across the JSON boundary, not the linter — catches the mismatch; it surfaces only as a render-time coercion issue on the client. Acceptable given the documented tradeoff, but worth a validation pass in the server DSL/CI fixtures to keep literal payload kinds aligned with their declared T.
- **Fix:** Add server-side (or fixture-contract) validation that a Literal's JsonPrimitive kind matches the field's declared T where statically known, so type drift is caught before it ships.

## Runtime & renderer robustness — grade C
*Unknown-node decoding and fallback dispatch are solid, but the broader renderer never has an error boundary and no recursion depth limit, and the flagship no-crash promise is untested end-to-end.*

### 🟠 HIGH — No error boundary around renderer execution — the "never crash" promise covers only unknown discriminators, not renderer failures _(verified: CONFIRMED)_
- **Where:** `runtime/src/commonMain/kotlin/dev/sdui/kmp/runtime/RenderNode.kt:20`
- **Problem:** RenderNode structurally guarantees no-crash ONLY for the unknown-node dispatch: if `registry.rendererFor(node)` returns null it renders the fallback. But when a renderer IS found it calls `renderer.Render(node, modifier)` directly with no try/catch (RenderNode.kt:22). Any exception thrown inside a registered renderer's composable brings down the whole Compose tree. I confirmed via grep that there is not a single try/catch or runCatching anywhere in runtime/ or widgets-*/ source. This is a fundamental gap for a framework whose architecture is built on pluggable third-party widgets-* modules (widgets-core, -forms, -media, -nav, -native-map, plus arbitrary host-authored ones): a bug in ANY renderer, or a malformed-but-known node, defeats VISION invariant #3. The shipped leaf resolvers are defensive (safe `as?` casts, empty-string fallbacks in ValueResolver.kt and TokenResolvers.kt), so the promise happens to hold for the built-in widgets today — but the framework offers no isolation mechanism for the widgets it is explicitly designed to host.
- **Fix:** Wrap renderer.Render in a Compose-safe error boundary (e.g. a recomposition-scoped runCatching that falls back to node.fallback / nothing and fires telemetry.onUnknownNode-style reporting), or document and enforce a hard contract that renderers must never throw and provide a lint/wrapper that guarantees it. At minimum, catch in RenderNode and route to the fallback path so a single bad widget cannot blank/crash the screen.

### 🟠 HIGH — Unbounded rendering recursion — deeply nested server tree causes StackOverflowError, crashing the client · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `runtime/src/commonMain/kotlin/dev/sdui/kmp/runtime/RenderNode.kt:26`
- **Problem:** Container renderers recurse into children with zero depth guard: ColumnRenderer.kt:37-39 calls RenderNode per child, LazyListRenderer.kt:54 renders the item template, and RenderNode.kt:26 recurses through the fallback chain. There is no depth counter, no node budget, and no cap at deserialization (confirmed: grep for maxDepth/maxNodes/nodeLimit across protocol/runtime/widgets/transport returns nothing; SduiJson.kt sets no nesting limit on the Json decoder). A server (or a compromised/misbehaving intermediary, or simply a buggy layout) that emits a deeply nested Column-in-Column tree, or a long fallback chain, overflows the stack during composition and hard-crashes the app — a direct violation of the "client never crashes" non-negotiable, from a source the framework treats as untrusted. The same unbounded recursion exists in TreePatchEngine.kt (replaceNode/appendInto/removeNodes, lines 41-76), so a deep tree also overflows when applying a live TreePatch.
- **Fix:** Enforce a maximum tree depth (and ideally a total node budget) either at decode time in SduiJson or via a depth-tracking CompositionLocal in RenderNode; beyond the limit, render the fallback / a diagnostic and emit telemetry rather than recursing. Apply the same cap in TreePatchEngine.

### 🟠 HIGH — The central "never crash on unknown node" invariant has no end-to-end composition test _(verified: CONFIRMED)_
- **Where:** `runtime/src/commonTest/kotlin/dev/sdui/kmp/runtime/WidgetRegistryTest.kt:33`
- **Problem:** The flagship promise is verified only at the registry-lookup level: WidgetRegistryTest asserts `rendererFor(UnknownUiNode)` returns null and that a client newer than handledVersions falls through to null. There is NO test that actually composes an unknown node (or a version-mismatched node) through RenderNode and asserts the fallback renders and nothing throws, and no test of the fallback-chain recursion or a deeply nested tree. I confirmed there are zero Compose UI tests (runComposeUiTest/createComposeRule) anywhere in runtime/ or widgets-*/ — the only Compose test harness in the repo lives in tooling-snapshot (A11ySnapshotTest) and does not exercise the RenderNode unknown/fallback/deep-tree paths. For a framework meant to last 5-10 years, the actual runtime robustness path is unverified by the test suite; the invariant is asserted by construction and prose, not by an executable test.
- **Fix:** Add commonTest Compose UI tests that render: (a) an UnknownUiNode with and without a fallback, (b) a known node whose client version is out of range, (c) a renderer that throws, and (d) a pathologically deep tree — asserting no crash and correct fallback in each case.

### 🟡 MEDIUM — ActionDispatcher does not contain exceptions; a throwing SubmitHandler crashes via uncaught coroutine
- **Where:** `runtime/src/commonMain/kotlin/dev/sdui/kmp/runtime/ActionDispatcher.kt:101`
- **Problem:** DefaultActionDispatcher.dispatch (ActionDispatcher.kt:41) and submit/performWithRetry have no try/catch. performWithRetry calls `handler.submit(...)` (line 101) and lets any thrown exception propagate. The SubmitHandler interface (SubmitHandler.kt:28) is a plain suspend fun that permits throwing — only the shipped KtorSubmitHandler happens to catch Throwable and return SubmitResult.Failure (KtorSubmitHandler.kt:40-49). Buttons dispatch via `scope.launch { dispatcher.dispatch(node.action) }` on a rememberCoroutineScope (ButtonRenderer.kt:44) with no exception handler, so a custom or future SubmitHandler that throws produces an uncaught coroutine exception and crashes the app. The retry/optimistic/rollback logic is otherwise solid (overflow-capped backoff shift at line 107, snapshot+rollback at 114-128).
- **Fix:** Wrap `handler.submit(...)` in runCatching and map failures to SubmitResult.Failure(cause=…) inside performWithRetry, so the dispatcher's own contract — not each handler's discipline — guarantees no exception escapes. Consider a supervisor/handler on the launch in ButtonRenderer as defense in depth.

### ⚪ LOW — LazyList paged source is an unimplemented stub that silently renders empty · ⚡scalability
- **Where:** `widgets-core/src/commonMain/kotlin/dev/sdui/kmp/widgetscore/LazyListRenderer.kt:82`
- **Problem:** ListSource.Paged returns emptyList() with a comment that the pager "lands with transport hardening" (LazyListRenderer.kt:82-86). Any screen a server ships with a Paged list will render only its emptyState — a silent, protocol-valid feature that does nothing at runtime. Additionally, ListSource.Bound (line 78) materializes the entire JsonArray from state and maps every element to JsonObject before handing to LazyColumn; the lazy layout windows composition but the full backing list is held in memory, so very large bound lists carry a full-array memory cost. Not a crash, but a production-readiness/scale gap to track before large-list screens ship.
- **Fix:** Either implement the pager or make Paged fail loudly in dev/telemetry so it isn't mistaken for a working feature; document the in-memory materialization cost of Bound lists and cap or window it for large datasets.

## Server rendering, DSL & studio-server — grade C
*Studio-server has a sound deterministic DSL and clean store/route separation, but ships production-blocking scalability and correctness gaps: a full-table scan on screen listing, an incomplete production migration set, no global error handler, and a ReDoS-exposed unauthenticated assign path.*

### 🔴 CRITICAL — GET /admin/screens loads every historical version body of every screen into JVM heap · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/ScreenStore.kt:59`
- **Problem:** listScreens builds versionsByScreen with `ScreenVersions.selectAll().associate { it[id] to it[versionNumber] }`. selectAll() materializes ALL columns of ScreenVersions — including the unbounded `body_json TEXT` column — for EVERY version of EVERY screen ever published, on every listing request, purely to translate a current_version_id UUID into an integer version number. Memory and I/O cost is O(screens × versions_per_screen × body_size) per request. A 5-10 year deployment with thousands of screens and deep histories will OOM or stall the pool on a routine listing call. This is the single most severe scalability defect in the dimension.
- **Fix:** Project only the two needed columns (`.select(ScreenVersions.id, ScreenVersions.versionNumber)`), or better, replace the full scan with a single query/join that resolves version_number for the specific current_version_id per screen (one row per screen). Never materialize body_json in a listing path.

### 🟠 HIGH — Production Flyway migration set is missing 9 of 15 tables; runtime relies on createMissingTablesAndColumns _(verified: CONFIRMED)_
- **Where:** `studio-server/db/migrations/V1__studio_initial.sql:1`
- **Problem:** StudioDatabase.kt:21 documents V1__studio_initial.sql as "the production-shaped path applied by Flyway," but V1 creates only 6 tables (editor_accounts, screen_definitions, screen_versions, screen_drafts, screen_audit_log, editor_sessions). The 9 tables the code actually registers in SchemaUtils.createMissingTablesAndColumns (StudioDatabase.kt:54-72) — experiments, experiment_variants, audiences, experiment_audiences, experiment_assignments, permissions, roles, role_permissions, editor_roles — have no migration. There is also no Flyway dependency or invocation anywhere in the module (grep found none); production would silently fall back to Exposed's createMissingTablesAndColumns, which cannot express column type changes, drops, backfills, or the additive-versioning discipline this project mandates. A real Postgres deploy following the documented path is missing the entire experiments and RBAC surface.
- **Fix:** Add migrations V2+ covering all 9 missing tables, wire an actual Flyway runner at boot, and remove createMissingTablesAndColumns from the production path (keep it only for the H2 dev fallback). Keep the SQL and Exposed schema in a CI-enforced lockstep check.

### 🟠 HIGH — No StatusPages handler — store-layer exceptions surface as bare 500s with no error contract _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/StudioModule.kt:89`
- **Problem:** studioModule installs ContentNegotiation, WebSockets, Auth, and Metrics but no StatusPages plugin. Several store methods throw on normal race/edge conditions that then escape the route with no ErrorResponse: publishDraft `error("no draft to publish")` (ScreenStore.kt:204) when two requests publish concurrently, the version-number unique-constraint violation in appendVersion, and the ExperimentAssignments PK violation in persistAssignment. All become uncaught 500s with inconsistent (non-JSON) bodies, defeating the otherwise-careful ErrorResponse contract used elsewhere. There is no uniform mapping of ExposedSQLException/IllegalStateException to 4xx/5xx.
- **Fix:** Install StatusPages with explicit mappings (IllegalArgumentException→400, IllegalStateException→409/404, ExposedSQLException→409/500) returning the standard ErrorResponse shape, and log with request id.

### 🟠 HIGH — Unauthenticated /screens/{id}/assign is a ReDoS + unbounded-write vector; documented regex validation is not implemented · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/experiments/AudiencePredicate.kt:72`
- **Problem:** AudiencePredicate.MatchesRegex.evaluate does `runCatching { Regex(pattern).matches(raw) }` — recompiling an editor-supplied regex on every evaluation. On the assign hot path this runs per predicate per request against client-controlled input (X-Sdui-Context-* headers). The KDoc (AudiencePredicate.kt:20 and the schema comment) claims "the route layer compiles each MatchesRegex pattern once at audience-create time and rejects pathological patterns," but the actual create-audience validation is `req.predicate.evaluate(emptyMap())` (ExperimentRoutes.kt:188). With an empty context, MatchesRegex short-circuits to false before the pattern is ever compiled, so a catastrophic-backtracking pattern is accepted and stored. It then compiles and runs on every /screens/{id}/assign call — an unauthenticated endpoint (StudioModule.kt:104, installScreenAssignRoute has no authenticate block) — giving an attacker a stored ReDoS amplifier triggered by anonymous traffic. Separately, that same GET performs a DB write (persistAssignment) and creates one experiment_assignments row per distinct X-Sdui-Client-Id with no rate limiting (grep: no RateLimit plugin), allowing unbounded table growth.
- **Fix:** Actually compile every MatchesRegex at create time and reject on failure; cache compiled Regex objects instead of recompiling per request; add a pattern-length/complexity guard or a timeout-bounded matcher. Put the assign endpoint behind a service-to-service token and add rate limiting; the read-heavy variant of the flow should avoid writing on GET.

### 🟡 MEDIUM — Version-number allocation via max()+1 races under concurrent publish/revert · ⚡scalability
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/ScreenStore.kt:263`
- **Problem:** appendVersion computes next version as `max(version_number)+1`. No explicit transaction isolation is configured (grep confirms DB default, READ COMMITTED on Postgres). Two concurrent publishes/reverts to the same screen both read the same max and insert the same version_number; the screen_versions_screen_version_uq unique index (correctly) rejects the second, but the loser throws an unhandled ExposedSQLException → bare 500 (no StatusPages). Correctness of the history is preserved, but the operation is not retry-safe and fails noisily under contention on a hot screen.
- **Fix:** Serialize per-screen version allocation with SELECT ... FOR UPDATE on the screen_definitions row (or a DB sequence), and/or catch the unique violation and retry. Set an explicit isolation policy for these write paths.

### 🟡 MEDIUM — Audit log is not transactionally coupled to the mutation it records
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/routes/ScreensAdminRoute.kt:152`
- **Problem:** publishDraft (its own transaction) commits the new version and deletes the draft, then AuditStore.append runs in a separate transaction (AuditStore.kt:79), then the notifier fires. If the audit insert fails or the process dies between commit and append, the version is published but has no audit row — contradicting the store's documented invariant "every Studio mutation produces exactly one row" — and no live push is sent. The client receives a 500 and cannot cleanly retry because the draft was already consumed (a retry hits ScreenStore.draftFor→404). Same non-atomic pattern applies to draft PUT and delete.
- **Fix:** Perform the mutation and its audit insert inside a single transaction (pass the audit write into the store method or wrap both in one newSuspendedTransaction). Fire the notifier only after that combined commit.

### 🟡 MEDIUM — Sticky assignment persistence uses read-then-insert instead of an upsert, racing itself into a 500 · ⚡scalability
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/experiments/ExperimentStore.kt:303`
- **Problem:** persistAssignment selects the (experimentId, clientId) row then inserts if absent. Two concurrent first-time assign() calls for the same client both read null and both insert, violating the composite PK → unhandled ExposedSQLException → 500. The RBAC layer already uses insertIgnore for exactly this pattern (PermissionStore/RbacBootstrap), but the assignment hot path does not. Because pickByWeight is deterministic both would choose the same variant, so the correct behavior is idempotent — the code just fails to express it safely.
- **Fix:** Use insertIgnore / INSERT ... ON CONFLICT DO NOTHING and re-read, mirroring the RBAC stores, so concurrent first assignments converge silently.

### ⚪ LOW — Variant weight-sum invariant is checked across transaction boundaries (TOCTOU)
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/experiments/ExperimentRoutes.kt:125`
- **Problem:** The add-variant route calls store.listVariants (one transaction) to sum existing weights, checks newTotal <= 100, then calls store.addVariant (a separate transaction). Two concurrent variant adds can each pass the check and both insert, pushing the documented "weights sum to 100" invariant above 100. pickByWeight scales by totalWeight so distribution self-corrects, but the stored invariant and any reporting that assumes sum==100 are violated.
- **Fix:** Perform the sum check and the insert in a single transaction, and enforce the ceiling with a constraint or a per-experiment lock.

### ℹ️ INFO — Unknown enum wire values silently coerced instead of flagged
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/experiments/ExperimentStore.kt:36`
- **Problem:** ExperimentStatus.parse defaults any unknown status string to Draft (inactive), and AuditStore.rowToEntry (AuditStore.kt:153) defaults unknown actions to Drafted. Intended as defence-in-depth, but it means a corrupted or typo'd status column silently makes an experiment inactive, and a bad audit action is silently relabeled — masking data corruption rather than surfacing it via logs/metrics.
- **Fix:** Log a warning (with the offending value and row id) whenever the fallback branch is taken, so silent coercion is observable.

## Scalability & transport — grade D
*The live/transport layer has solid coroutine and cache hygiene but its horizontal-scaling core is non-functional: nothing publishes to the LiveBus, clients never reconnect, and fan-out has head-of-line blocking plus an unbounded registry.*

### 🔴 CRITICAL — Cross-process fan-out is never wired: LiveBus.publish() has zero production callers · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/routes/WebSocketPublishNotifier.kt:33`
- **Problem:** The entire horizontal-scaling story rests on PostgresLivePublisher/LiveBus, but nothing in production ever calls LiveBus.publish(). WebSocketPublishNotifier.screenPublished() calls publisher.broadcast(screenId, ...) directly on the studio's LOCAL WebSocketLivePublisher (line 33) — it never touches the bus. sample-server only ever SUBSCRIBES to the bus and bridges it into its local WS publisher (Main.kt bridgeAllTopics, line 265). A repo-wide grep for `.publish(` finds callers only in tests (PostgresLivePublisherTest, InProcessLiveBusTest). Net effect: a screen published on the studio JVM reaches only the WS clients connected to that same JVM; sample-server instances (where real clients subscribe via /live/screens/{id}) receive nothing, because no one publishes to the Postgres channel they LISTEN on. The subscribe half of the cross-JVM path is built and the publish half is missing, so multi-instance live updates do not work at all.
- **Fix:** Wire the studio publish path (and any other publisher) through the shared LiveBus.publish(topic, event) instead of, or in addition to, the local WebSocketLivePublisher.broadcast. Add an integration test that boots two modules sharing one bus and asserts a publish on JVM A reaches a WS subscriber on JVM B.

### 🟠 HIGH — WebSocketLiveSource never reconnects; clients go permanently deaf after the first disconnect · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `transport-live/src/commonMain/kotlin/dev/sdui/kmp/transport/live/WebSocketLiveSource.kt:74`
- **Problem:** runSession() opens one WebSocket and loops over s.incoming exactly once. When the socket closes for any reason (server rolling deploy, load-balancer idle timeout, network blip), the for-loop ends, the finally sets session=null, and the coroutine completes. pump still holds the now-completed Job, so start() (guarded by `if (pump != null) return`, line 54) will not restart it. There is no reconnect loop, no backoff, no re-subscribe. The KDoc claims the source 'survives reconnection,' but no reconnection logic exists. Under real traffic every deploy or transient drop silently stops live updates for every connected client until the app process is restarted.
- **Fix:** Wrap runSession() in a reconnect loop with capped exponential backoff + jitter, and on reconnect signal the host to re-fetch the screen over HTTP to close the no-replay gap. Add jitter to avoid a reconnection thundering herd when a server restarts and drops all sockets simultaneously.

### 🟠 HIGH — Broadcast fan-out is sequential and blocking — one stuck subscriber stalls the whole topic · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `transport-live/src/jvmMain/kotlin/dev/sdui/kmp/transport/live/WebSocketLivePublisher.kt:73`
- **Problem:** broadcast() iterates the session snapshot and awaits session.send(Frame.Text(payload)) sequentially (lines 73-91). Ktor's send suspends when a peer's outgoing buffer is full (a slow or non-reading client applying TCP backpressure). A single stuck subscriber therefore blocks delivery to every subscriber later in the iteration order, and because there is no per-send timeout the broadcast can suspend indefinitely. Concurrent broadcasts to the same topic each independently block on that same slow peer. This is classic head-of-line blocking: a large subscriber fan-out on a hot screen degrades to the speed of its slowest/most-stalled client, and dead-but-not-yet-detected peers are only reaped after send finally throws.
- **Fix:** Fan out sends concurrently (launch per session or a bounded channel per session) with a send timeout / try-with-deadline so a slow peer is dropped rather than blocking others. Consider a bounded per-session outbound queue with drop-oldest so a slow client cannot exert backpressure on the broadcaster.

### 🟠 HIGH — Per-topic registry grows unbounded — empty TopicState is never removed, keyed by client-supplied id · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `transport-live/src/jvmMain/kotlin/dev/sdui/kmp/transport/live/WebSocketLivePublisher.kt:54`
- **Problem:** register() lazily inserts a TopicState via computeIfAbsent (line 46) but unregister() only removes the session from the set (line 56); it never removes the topic key when the set becomes empty. The `topics` ConcurrentHashMap therefore retains one entry per distinct topic id ever seen, forever. The topic id comes straight from the URL path {id} (LivePublisherRoute.kt:49), so any authenticated client can mint unlimited distinct topics by subscribing to arbitrary ids, each leaving a permanently-retained empty TopicState (set + Mutex). Over a 5-10 year lifespan this is a slow memory leak and a cheap amplification vector for an authenticated attacker.
- **Fix:** In unregister, remove the topic key when the set becomes empty (guard the check-and-remove against a concurrent register, e.g. compute/merge on the ConcurrentHashMap under the topic lock). Optionally validate {id} against a known screen-id allowlist before register.

### 🟡 MEDIUM — Postgres path multiplexes all topics through one channel and one 64-slot drop-oldest buffer · ⚡scalability
- **Where:** `transport-live/src/jvmMain/kotlin/dev/sdui/kmp/transport/live/PostgresLivePublisher.kt:96`
- **Problem:** Every publisher NOTIFYs the single `sdui_live` channel (CHANNEL, line 235) and every JVM's one dedicated LISTEN connection receives, decodes, and emits EVERY event for EVERY topic into one shared MutableSharedFlow with extraBufferCapacity=64 and BufferOverflow.DROP_OLDEST (lines 96-100); subscribe(topic) filters client-side afterward (line 224). Consequences at scale: (1) a burst on one hot topic can silently evict pending events of unrelated topics from the shared 64-slot buffer — cross-topic interference with no visibility; (2) each instance does O(all-events) JSON decode work regardless of which topics it actually serves; (3) Postgres NOTIFY funnels through a single cluster-global async-notify queue, a hard ceiling under high publish rate. Combined with the no-replay reconnect (a DB blip up to MAX_BACKOFF_MILLIS=30s, line 252, drops events with no catch-up signal to already-connected subscribers), live updates can be lost silently.
- **Fix:** Use per-topic (or sharded) NOTIFY channels or an external broker (Redis/NATS) for high fan-out; give the shared flow a much larger or per-topic buffer and surface a dropped-event metric. On listener reconnect, emit a resync signal that forces connected subscribers to re-fetch over HTTP rather than relying only on the next fresh subscription.

### 🟡 MEDIUM — Disk cache grows without bound: no eviction, no size cap, timestamp hardcoded to 0
- **Where:** `transport-http/src/commonMain/kotlin/dev/sdui/kmp/transport/http/HttpScreenSource.kt:130`
- **Problem:** JvmScreenDiskCache is a flat directory with one <sha256(url)>.json file per URL and no eviction, size cap, or LRU trimming (JvmScreenDiskCache.kt). CacheEntry carries storedAtEpochMs 'useful for TTL eviction' (CacheEntry.kt:22), but HttpScreenSource.persistToDisk hardcodes storedAtEpochMs = 0L (line 130), so even a future TTL policy has no real timestamps to work with. Only AndroidScreenDiskCache is safe (OS evicts cacheDir); the JVM/desktop cache lives in ~/.cache/sdui-kmp/screens and is never reclaimed, so on a long-running desktop client the cache directory grows monotonically with the number of distinct screen URLs visited.
- **Fix:** Thread a Clock so entries get real timestamps, and add a bounded eviction policy (max entries or max bytes, LRU/TTL) to the disk cache implementations that are not backed by OS cache eviction.

### ⚪ LOW — bridgeAllTopics binds a hardcoded topic list; dynamic screens are not fanned out cross-process · ⚡scalability
- **Where:** `samples/sample-server/src/main/kotlin/dev/sdui/kmp/sample/server/Main.kt:265`
- **Problem:** ensureLiveBridge() bridges only the fixed SAMPLE_LIVE_TOPICS list (Main.kt:241-249) from the bus into the local WS publisher. The KDoc on bridgeTopic advertises an on-demand 'bridge per topic when a client first subscribes' pattern (LiveBusBridge.kt), but the WebSocket route's register callback (LivePublisherRoute.kt:55) does not invoke it. So any screen id not in the static list receives no cross-process events even once the publish path is fixed — every new screen requires a code change and redeploy, which does not scale to a content-driven catalog.
- **Fix:** Bridge topics dynamically from installLiveScreensRoute's register callback (create the bus subscription on first subscriber, cancel it on last disconnect) instead of a compile-time list.

## Security — grade C
*Sound auth design and correct RBAC/JWT primitives, but the production studio-server ships with a hard-coded fallback signing secret, no login rate limiting, and unbounded deserialization of untrusted UI trees.*

### 🔴 CRITICAL — studio-server production path signs/verifies JWTs with a hard-coded fallback HMAC secret _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/auth/StudioJwt.kt:59`
- **Problem:** StudioJwt.fromEnv() (lines 62-66) reads STUDIO_JWT_SECRET and, if it is unset/blank, silently falls back to the compiled-in public constant FALLBACK_SECRET = "studio-only-not-a-real-secret-please-rotate" (line 59). This is the production wiring: Main.main() -> studioModule() -> StudioModule.kt:62 defaults jwt = StudioJwt.fromEnv(). Because the algorithm is symmetric HMAC256 (line 25), the verifier key equals the signing key, so anyone who reads this open-source constant can forge a valid admin JWT (arbitrary sub/role/jti) against any deployment that forgot to set the env var. There is no fail-fast, only a KDoc warning. A dedicated, correct RS256 issuer/verifier already exists in :auth-rs256 but is not wired into studio-server at all. The whole admin surface (screens publish, delete, RBAC, editor management) sits behind this token.
- **Fix:** Fail fast at boot if STUDIO_JWT_SECRET (or the RS256 keys) is absent in any non-dev profile; never fall back to a literal secret. Replace HMAC256 with the existing Rs256JwtIssuer/Rs256JwtVerifier so the verifier only holds the public key and the signing key stays server-side.

### 🟠 HIGH — No rate limiting or lockout on the studio-server admin login endpoint · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/StudioModule.kt:79`
- **Problem:** studioModule() installs Metrics, ContentNegotiation, WebSockets and Authentication but no RateLimitPlugin or CsrfPlugin. The unauthenticated POST /admin/auth/login route (EditorAuthRoute.kt:25) has no throttling, no failed-attempt lockout, and no CAPTCHA, so an attacker can run unlimited credential-stuffing/brute-force against admin bcrypt hashes. The auth-rs256 module ships a RateLimitPlugin and CsrfPlugin, but only samples/sample-server wires them (Main.kt:347,356) - the real admin backend does not. bcrypt cost 12 slows each guess but does not stop a distributed campaign, and the missing throttle also makes login a cheap DoS vector (each attempt forces a ~250ms bcrypt).
- **Fix:** Install RateLimitPlugin (keyed on client IP and on email) around /admin/auth/login, add progressive lockout/backoff on repeated failures, and consider a shared-store limiter for multi-replica deployments.

### 🟠 HIGH — Untrusted UiNode/Screen trees are deserialized with no depth or size limit (DoS) · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/routes/ScreensAdminRoute.kt:107`
- **Problem:** PUT /admin/screens/{id}/draft calls call.receive<JsonElement>() (line 107) and hands the tree to DraftValidator.validate(), which runs SduiJson.decodeFromJsonElement(Screen.serializer(), body) (DraftValidator.kt:63). kotlinx.serialization JSON parsing and polymorphic UiNode decoding are recursive-descent, so a deeply nested payload (e.g. thousands of nested containers) throws StackOverflowError. That is a java.lang.Error, NOT a SerializationException/IllegalArgumentException, so neither DraftValidator's catch blocks (lines 66-69) nor the route's catch (line 108) intercept it - the request aborts with a 500 and can destabilise the worker. No Ktor request-body size cap is configured on ContentNegotiation either, so large bodies also inflate memory. The same unbounded parseToJsonElement runs on the unauthenticated /screens/{id}/assign path (ExperimentRoutes.kt:264). This is exactly the untrusted-deserialization risk the protocol design is meant to bound.
- **Fix:** Enforce a max request body size (Ktor's install(RequestValidation)/content-length check or a size-limited stream) and a maximum nesting depth before/while decoding untrusted trees; treat StackOverflowError/overflow as a 400, not a 500.

### 🟠 HIGH — CSRF and rate-limit plugins respond in onCall without short-circuiting the pipeline _(verified: CONFIRMED)_
- **Where:** `auth-rs256/src/main/kotlin/dev/sdui/kmp/auth/rs256/CsrfPlugin.kt:118`
- **Problem:** In both CsrfPlugin (lines 115-125) and RateLimitPlugin (lines 99-106) the createRouteScopedPlugin onCall handler writes a 403/429 response but never calls finish() or otherwise halts the pipeline. In Ktor 3.x, responding inside onCall does not prevent the matched route handler from subsequently executing; only the handler's own second call.respond throws. For handlers whose side effects run before they respond - e.g. sample-server's login route which does call.receive() + SessionStore.issue() + JWT issuance before respondText (Main.kt:362-377) - those side effects (session-row writes, token minting) can still occur on a request that was supposed to be CSRF-rejected or rate-limited, even though the client only sees the 403/429. The existing tests only assert the status code, so they do not catch this. Verify against Ktor 3.0.2 semantics; if confirmed, every consumer of these library plugins is affected.
- **Fix:** After responding in the plugin, explicitly short-circuit (throw a handled early-return, or restructure to a phase that stops routing) and add a test asserting the guarded handler's side effect does NOT occur when the request is blocked.

### 🟡 MEDIUM — Unauthenticated screen-assignment endpoint leaks published screen bodies and allows targeting manipulation
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/experiments/ExperimentRoutes.kt:238`
- **Problem:** installScreenAssignRoute(service) is mounted at line 238, OUTSIDE the authenticate block that closes at line 236, so GET /screens/{id}/assign (lines 250-276) is fully unauthenticated. Any caller who supplies an X-Sdui-Client-Id header receives the full published screen body JSON, and arbitrary attacker-controlled X-Sdui-Context-* headers (buildContext, lines 282-292) drive audience-predicate evaluation and sticky variant assignment - letting an attacker enumerate screen content, force themselves into any A/B variant, and poison sticky-assignment records. The KDoc acknowledges there is no service-to-service token yet. There is no rate limit here either.
- **Fix:** Gate the assign route behind a service-to-service credential (mTLS or a signed service token) and rate-limit it; do not treat inbound X-Sdui-Context-* headers as trusted targeting input from unauthenticated callers.

### 🟡 MEDIUM — RS256 verification has no key-rotation support and silently generates ephemeral keys · ⚡scalability
- **Where:** `auth-rs256/src/main/kotlin/dev/sdui/kmp/auth/rs256/Rs256JwtVerifier.kt:18`
- **Problem:** Rs256JwtVerifier holds a single RSAPublicKey and builds JWT.require(Algorithm.RSA256(publicKey,null)) (lines 27-32) - it never inspects the token's kid header, even though the issuer stamps kid and the JWKS endpoint publishes it (JwksRoute.kt:52-53). This means key rotation cannot be done without downtime: there is no dual-key (old+new) verification window, so rotating the signing key instantly invalidates every in-flight token. Compounding this, loadOrGenerateKeyPair (KeyPairLoader.kt:37-44) silently generates an ephemeral in-memory RSA pair when RSA_PRIVATE_KEY/RSA_PUBLIC_KEY are missing and only logs a warning - a misconfigured prod deploy will boot 'successfully' but every issued token becomes unverifiable after any restart, and across replicas tokens issued by one instance won't verify on another.
- **Fix:** Support a multi-key verifier keyed by kid (accept old and new during a rotation window); fail fast at boot in production profiles when the configured RSA keys are absent rather than generating ephemeral ones.

### ⚪ LOW — Login is vulnerable to timing-based account enumeration
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/EditorAccountStore.kt:117`
- **Problem:** EditorAccountStore.authenticate() returns null immediately when the email row is absent (lines 122-124) and only performs the ~250ms bcrypt verifyPassword when a row exists (line 126). Although the route returns an identical 'invalid credentials' message for both cases (EditorAuthRoute.kt:38), the response-time difference (fast miss vs slow bcrypt) lets an attacker enumerate which admin emails exist, undermining the stated goal in the method KDoc that callers must not distinguish the two.
- **Fix:** On the email-miss path, run a bcrypt verify against a fixed dummy hash so authentication takes constant time regardless of whether the account exists.

### ⚪ LOW — VaultSecretsProvider is a stub that silently serves environment secrets while appearing Vault-backed
- **Where:** `auth-rs256/src/main/kotlin/dev/sdui/kmp/auth/rs256/SecretsProvider.kt:63`
- **Problem:** VaultSecretsProvider.get() (lines 63-71) never contacts Vault; it logs a TODO and returns fallback.get(key), i.e. System.getenv. It is a shipped public class whose name implies production-grade secret retrieval but which quietly downgrades to env vars. A deployment that wires this expecting Vault-backed rotation/leasing gets none, with only a warn-level log to notice.
- **Fix:** Either implement the Vault HTTP integration or make the stub throw/NotImplemented outside dev profiles so a Vault-configured deployment fails loudly instead of silently reading env vars.

### ℹ️ INFO — Client IP for audit and rate-limit keying is taken from origin without a trusted-proxy config · ⚡scalability
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/routes/RouteAuth.kt:72`
- **Problem:** actorIp() reads request.origin.remoteHost, but studioModule() never installs Ktor's XForwardedHeaders/ForwardedHeaders plugin. Behind a load balancer this records the LB's address for every audit row, and any future IP-based rate limiting would bucket all traffic into one key. Conversely, enabling forwarded-header parsing without restricting it to trusted proxies would let clients spoof X-Forwarded-For.
- **Fix:** When deployed behind a proxy, install ForwardedHeaders restricted to the known LB source, and derive audit IP / rate-limit keys from the validated value.

## Build, CI, testing & release — grade C
*Solid CI scaffolding (multi-OS matrix, iOS link stage, schema-snapshot + dependency-rule gates that actually run, pinned versions, signed publishing) undercut by gates the project's own governing docs depend on not being enforced: Detekt runs for only 1 of 30 modules, a whole module's 38 tests are disabled, releases publish without a test/schema gate, and the widget-rendering layer is largely untested.*

### 🟠 HIGH — Static analysis (Detekt/ktlint) is only enforced for :tooling-cli in CI, not the other 29 modules _(verified: CONFIRMED)_
- **Where:** `.github/workflows/ci.yml:59`
- **Problem:** Detekt is wired into every module's `check` task (sdui.detekt.gradle.kts:76-80) with `ignoreFailures = false`. But CI never runs the aggregate `check`. The `linux` job runs only `assemble testClasses` (ci.yml:85) and `jvmTest test` (ci.yml:88) — neither of which depends on Detekt. The only `check` invoked anywhere in CI is `:tooling-cli:check` (ci.yml:59), which runs Detekt for that single module. So the ktlint-formatting + Detekt gate that DEFINITION_OF_DONE.md mandates ("`./gradlew check` passes locally") is effectively unenforced for :protocol, :runtime, all widgets-*, all transport-*, :server, and every other module. Style/complexity regressions land silently as long as the author skips local `check`.
- **Fix:** Add an explicit CI step that runs Detekt across all modules (e.g. `./gradlew detekt detektMetadataMain detektJvmMain` project-wide, or a root `check` that fans out), or promote the aggregate `check` to a first-class CI step so the wired gates actually fire on every module.

### 🟠 HIGH — studio-web's entire test source set is disabled and never executes in CI _(verified: CONFIRMED)_
- **Where:** `studio-web/build.gradle.kts:72`
- **Problem:** studio-web disables `wasmJsBrowserTest`, `wasmJsTest`, and the wasm test-compile sync tasks via `enabled = false` (build.gradle.kts:72-81). studio-web is a wasmJs-only module, so these are its only test tasks — its 38 `@Test` methods across 4 files (StudioApiTest, ExperimentsApiTest, TreeOpsTest, TreeMutatorTest) never run. The test file itself documents this: "this test source set is currently disabled ... the tests still compile when enabled" (StudioApiTest.kt:22-25). These are dead tests that give a false sense of coverage for the studio editor's API-wrapper and tree-mutation logic.
- **Fix:** Either move these tests to a JVM test target so they execute (Ktor MockEngine and tree logic have no wasm dependency), or gate their disablement behind a tracked issue and exclude them from the reported test count. Do not leave 38 compile-only tests presented as coverage.

### 🟠 HIGH — Release workflow publishes to Maven Central with no test run and no schema-snapshot guard on the tagged commit _(verified: CONFIRMED)_
- **Where:** `.github/workflows/release.yml:52`
- **Problem:** The release job (release.yml:21-67) runs only `verifyRelease` (POM-metadata pre-flight) and then `publishToSonatype closeAndReleaseSonatypeStagingRepository`. It never runs the test suite, `verifyDependencyRules`, or `verifyProtocolSnapshot`. A tag can be pushed to any commit — not necessarily one that passed the main-branch CI — and it will be published and auto-promoted to Maven Central. For an additive-only protocol with a hard 'never break the wire' guarantee, cutting a release without re-verifying the protocol snapshot or running tests on the exact tagged tree is a significant release-engineering gap: a protocol violation or a red test on the tagged commit ships to Central irreversibly.
- **Fix:** Add `./gradlew verifyProtocolSnapshot verifyDependencyRules check` (or at minimum the schema guard + JVM tests) as a required step before `publishToSonatype` in release.yml, or make the release job `needs:` a green CI run pinned to the tagged SHA.

### 🟡 MEDIUM — Coverage gate is a single 60% merged floor with no per-module minimum; whole layers sit at 0% invisibly
- **Where:** `build.gradle.kts:454`
- **Problem:** The Kover verify rule is one aggregate bound `minValue = 60` over the merged report (build.gradle.kts:450-456). The floor comment itself admits widgets-*/transport-*/tooling-* are 'still unmeasured' (build.gradle.kts:423). Because it is a single merged number, well-tested modules (:protocol 64 tests, :runtime 69 tests) can carry the aggregate above 60% while entire modules sit at 0% — widgets-core (0 @Test), widgets-media (0), widgets-media-coil (0) are all included in the merged denominator yet contribute no coverage, and the gate stays green. There is no floor that would ever notice a module dropping to zero.
- **Fix:** Add a per-module minimum bound (even a low one, e.g. 30%) alongside the aggregate, so a module with no tests fails the gate. Tighten as the measured set grows, as ADR-0013 already anticipates.

### 🟡 MEDIUM — The core widget-rendering layer is essentially untested despite the 'client never crashes on unknown nodes' non-negotiable and the DoD golden-snapshot requirement
- **Where:** `widgets-core/build.gradle.kts:1`
- **Problem:** widgets-core has 0 @Test methods; widgets-media 0; widgets-media-coil 0. DEFINITION_OF_DONE.md's 'For tasks that add a widget' section requires a golden snapshot test for the default appearance AND one state-bound variant for every widget. A snapshot-testing module exists (:tooling-snapshot, 17 tests) but the widget modules do not use it. Fallback/unknown-node rendering behavior (non-negotiable #3) is exercised in :runtime (good, 69 tests) but the actual Compose NodeRenderer implementations in widgets-core ship with no automated test. For a framework meant to render server-driven trees for 5-10 years, the rendering surface is the highest-churn, highest-risk layer and it is the least tested.
- **Fix:** Wire :tooling-snapshot golden tests into widgets-core/media as the DoD already mandates, and add at least default-appearance snapshots per NodeRenderer. Enforce via the per-module coverage floor above.

### 🟡 MEDIUM — Wasm target tests disabled in four modules — under-tests a first-class target
- **Where:** `transport-http/build.gradle.kts:57`
- **Problem:** wasmJsTest/wasmJsBrowserTest are disabled with `enabled = false` in transport-http (build.gradle.kts:52-57), transport-cache (build.gradle.kts:40-45), tooling-telemetry (build.gradle.kts:33-34), and studio-web (build.gradle.kts:72-81), all citing skiko/Compose-Wasm OOM on the CI heap budget. For the multiplatform modules these tests still run on JVM via commonTest, so behavior is partially covered — but the project's stated invariant is 'no target gets features the others do not,' and the Wasm executable for these modules is compiled-but-never-test-executed. Given Wasm is a shipping target, a Wasm-specific regression (e.g. serialization or coroutine-dispatch divergence) would pass CI.
- **Fix:** Track the skiko OOM workaround to a real fix (bump heap, split test binaries, or use a non-Compose test entrypoint), and re-enable wasm test execution for the transport/telemetry modules that have no Compose dependency in their tests.

### 🟡 MEDIUM — No binary-compatibility validator for public API outside :protocol
- **Where:** `build-logic/src/main/kotlin/sdui.kmp.library.gradle.kts:18`
- **Problem:** `explicitApi()` is correctly enabled in both library conventions (sdui.kmp.library.gradle.kts:18, sdui.jvm.library.gradle.kts:8), which is good. The protocol wire contract is guarded by verifyProtocolSnapshot. But there is no Kotlin binary-compatibility-validator (apiDump/apiCheck) anywhere in the build (grep for apiValidation/apiCheck returns nothing). That means the public Kotlin/binary API of :runtime, :server, all widgets-*, and all transport-* modules — which adopters compile against for 5-10 years — can be broken (renamed/removed public symbol, changed signature) with no automated gate. Only the serialized protocol is protected.
- **Fix:** Apply the kotlinx binary-compatibility-validator to shipping library modules and commit `.api` dumps, wiring `apiCheck` into CI. This extends the additive-only discipline from the wire format to the compiled API surface.

### ⚪ LOW — The 'cheap early' schema-snapshot job transitively runs the full JVM test suite, duplicating the linux job's work
- **Where:** `build.gradle.kts:482`
- **Problem:** coverageGate is wired into `:tooling-cli:check` (build.gradle.kts:482-486) and depends on `koverVerify`, which aggregates every kover'd module and therefore forces all their JVM test tasks to run. So the schema-snapshot job (ci.yml:59, `./gradlew :tooling-cli:check`) — commented as running 'first and isolated ... before we burn matrix minutes elsewhere' (ci.yml:34-37) — actually executes the whole JVM test graph plus verifyDependencyRules. The `linux` job then runs `jvmTest test` again (ci.yml:88). JVM tests are executed twice per pipeline across two runners, and the 'cheap gate' is not cheap.
- **Fix:** Decouple coverageGate from `:tooling-cli:check` (run it as its own CI step in the linux job after tests), so the schema-snapshot gate stays a fast fail-fast check and JVM tests run once.

### ⚪ LOW — Gradle wrapper distribution is not checksum-pinned
- **Where:** `gradle/wrapper/gradle-wrapper.properties:3`
- **Problem:** gradle-wrapper.properties sets `validateDistributionUrl=true` but has no `distributionSha256Sum`. For a framework with a 5-10 year horizon and a Maven Central publishing pipeline, an unpinned wrapper distribution is a supply-chain weak point — a compromised or swapped distribution would not be detected by checksum.
- **Fix:** Add `distributionSha256Sum` for gradle-8.11.1-bin.zip to the wrapper properties (Gradle documents the checksum per release).

## Production ops & observability — grade C
*A genuinely strong observability foundation (Prometheus metrics, structured JSON logs, OTel/Sentry adapters, readiness probes, ops runbooks) undercut by real operate-in-prod blockers: insecure secret fallbacks that never fail fast, no graceful shutdown, an under-instrumented control plane, and an in-memory-only rate limiter.*

### 🔴 CRITICAL — studio-server silently falls back to a hard-coded, published HMAC signing secret when STUDIO_JWT_SECRET is unset _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/auth/StudioJwt.kt:59`
- **Problem:** StudioJwt.fromEnv() (lines 62-66) reads STUDIO_JWT_SECRET and, when absent/blank, returns a StudioJwt built on the compile-time constant FALLBACK_SECRET = "studio-only-not-a-real-secret-please-rotate" (line 59). The algorithm is HMAC256 (symmetric, line 25), so this constant is the full signing key: anyone reading the open-source repo can forge admin/editor JWTs for any studio-server deployment that forgot to set the env var. There is no fail-fast — the server boots normally, logs nothing, and the /admin/* RBAC surface, publish, and audit routes are all effectively unauthenticated. The studio-server is the control plane that writes screens served to every client, making this the highest-impact operational gap.
- **Fix:** Fail fast at boot: throw if STUDIO_JWT_SECRET is unset in any non-dev profile (gate the fallback behind an explicit SDUI_ENV=dev). Move to the RS256 asymmetric path already built in :auth-rs256 (as sample-server did) so the private key never ships in source. Emit a startup log line stating which key source is active.

### 🟠 HIGH — No graceful shutdown anywhere; the cross-process live bridge scope is leaked despite a comment claiming it is cancelled · ⚡scalability _(verified: CONFIRMED)_
- **Where:** `samples/sample-server/src/main/kotlin/dev/sdui/kmp/sample/server/Main.kt:225`
- **Problem:** Both servers run embeddedServer(Netty, ...).start(wait = true) (Main.kt:170, studio Main.kt:16) with no shutdownGracePeriod and no ApplicationStopping subscription — a repo-wide grep for ApplicationStopping / monitor.subscribe / addShutdownHook returns nothing. On SIGTERM (every rolling deploy / autoscale event) in-flight HTTP requests, WebSocket sessions, the Hikari pool, and the Postgres LISTEN/NOTIFY connection backing PostgresLivePublisher are all dropped abruptly. Worse, liveBridgeScope (line 227) is documented as "Cancelled on shutdown" (line 225) but is never cancelled anywhere — the SupervisorJob + Dispatchers.IO scope and its per-topic bridge coroutines leak. The Dockerfile sets ExitOnOutOfMemoryError but there is no drain path for normal termination.
- **Fix:** Configure Netty shutdownGracePeriod/shutdownTimeout, subscribe to ApplicationStopping to cancel liveBridgeScope, close the studioAssignmentClient, drain WebSocket sessions, and close Hikari pools. Verify SIGTERM handling under `docker stop` (default 10s grace).

### 🟠 HIGH — studio-server (the control plane) is materially less operable than the sample: no readiness probe, no request-id correlation, no access log, no login rate limit, and a silent in-memory H2 fallback _(verified: CONFIRMED)_
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/StudioModule.kt:89`
- **Problem:** studioModule() installs metrics + /health (always 200, lines 90-91) but a grep confirms it wires NONE of the production-prelude plugins the sample-server has: no installReadinessRoute (so k8s/LB cannot tell if the DB is actually reachable), no RequestIdPlugin (no request correlation id in logs/traces), no RequestLogPlugin (no structured access log), and no RateLimitPlugin on the unauthenticated /admin/auth login route (brute-force is wide open on the admin surface). Additionally StudioDatabase.buildFromEnv (StudioDatabase.kt:92-98) silently falls back to an in-memory H2 DB when JDBC_URL is unset — drafts, versions, RBAC, and the audit log are wiped on every restart — with only a System.err warning and no fail-fast. This is the service that stores who-published-what; losing the audit log silently is an operations and compliance hazard.
- **Fix:** Mirror sample-server's installProductionPrelude in studio-server: add /readiness backed by a real DB probe, request-id + structured access logging, and a login rate limiter. Make the H2 fallback fail-fast (or refuse to serve mutating routes) unless an explicit dev flag is set.

### 🟠 HIGH — Rate limiter is per-instance in-memory and its overflow policy lets an attacker flush all buckets · ⚡scalability _(verified: REFUTED)_
- **Where:** `auth-rs256/src/main/kotlin/dev/sdui/kmp/auth/rs256/RateLimitPlugin.kt:83`
- **Problem:** The only rate-limiting primitive keeps state in a per-process ConcurrentHashMap (lines 18-22, acknowledged in the KDoc). Behind a load balancer with N replicas the effective /auth/login quota is 5*N per minute — a real login-flood defense gap the moment the service scales horizontally. Separately, the eviction "fuse" drops the entire bucket map when it exceeds maxTrackedKeys (line 83); the default key is request.origin.remoteHost, which is client-controllable via X-Forwarded-For spoofing, so an attacker can inflate key cardinality past 10k and force buckets.clear() on demand, resetting everyone's window and defeating the limiter globally. No shared-store (Redis/etc.) implementation is provided, only mentioned in a comment.
- **Fix:** Provide a RateLimiterStore backed by Redis INCR+EXPIRE for multi-instance deployments and use it in the sample. Replace the drop-everything eviction with a bounded LRU, and derive the key from a trusted proxy header configuration rather than raw remoteHost.

### 🟡 MEDIUM — Alerting and deployment for the control plane are documentation-only — no alert rules, no studio-server image, no k8s/Helm manifests
- **Where:** `docs/ops/incident-response.md:32`
- **Problem:** incident-response.md and slos.md describe Prometheus alert queries (e.g. hikaricp_connections_active/max > 0.95) and PagerDuty routing (incident-response.md:250-253), and a grafana-dashboard.json exists, but there are NO machine-consumable alert rule files (no *.rules.yml / PrometheusRule anywhere) and no provisioning wiring — the alerts cannot actually fire. Deployment artifacts exist only for samples/sample-server (Dockerfile + docker-compose with Postgres + Flyway); the studio-server, which the runbook references with `kubectl exec deploy/sample-server`, has no Dockerfile, no compose, and there are no k8s manifests or Helm charts in the repo. The runbook also invokes a `sample-server-cli probe-db` binary that does not appear to exist.
- **Fix:** Commit Prometheus alert rules alongside the dashboard, add a studio-server Dockerfile + compose (and a migration runner), and provide at least reference k8s manifests wiring /readiness to the pod readinessProbe. Reconcile runbook commands with binaries that actually ship.

### 🟡 MEDIUM — Dual schema-management paths (runtime auto-DDL + Flyway) risk production drift
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/StudioDatabase.kt:54`
- **Problem:** StudioDatabase.connect() runs SchemaUtils.createMissingTablesAndColumns(...) on every boot (lines 54-73) against whatever DB it is pointed at, while the KDoc (lines 20-22) states Flyway's V1__studio_initial.sql is the "production-shaped path" and that "both keep the table set in lockstep" manually. Runtime auto-DDL against a production Postgres is risky (uncontrolled ALTERs, no review, no rollback) and the two sources of truth will drift as columns are added in code but forgotten in the migration (or vice-versa). No mechanism enforces the lockstep the comment relies on.
- **Fix:** Pick one authority in production: run Flyway as the sole migration path and disable createMissingTablesAndColumns outside tests (gate it behind the same dev flag as the H2 fallback), or generate the Flyway SQL from the Exposed schema in CI to guarantee parity.

### 🟡 MEDIUM — No consolidated config/secrets contract or boot-time validation; insecure defaults for DB credentials
- **Where:** `studio-server/src/main/kotlin/dev/sdui/kmp/studio/server/db/StudioDatabase.kt:120`
- **Problem:** Configuration is read ad-hoc via scattered System.getenv calls with silent insecure defaults: JDBC_USER defaults to "sdui_studio" and JDBC_PASSWORD defaults to empty string (StudioDatabase.kt:120-121), STUDIO_JWT_SECRET/JDBC_URL fall back silently, and sample-server's RS256 keypair is generated ephemerally at boot when SecretsProvider has nothing (Main.kt:88-89, making all issued tokens unverifiable after restart). There is no .env.example, no single documented env-var contract, and no fail-fast validation that required secrets are present before the server accepts traffic. Only EnvSecretsProvider is wired; the referenced VaultSecretsProvider is not implemented.
- **Fix:** Add a startup config-validation step that lists required vs optional env vars, fails fast on missing production secrets, logs the resolved (redacted) config, and ships a documented .env.example. Wire a real secrets-manager provider for the private signing key.

### ⚪ LOW — OTel adapter emits orphaned, back-dated spans with no trace-context propagation
- **Where:** `tooling-telemetry-otel/src/main/kotlin/dev/sdui/kmp/tooling/telemetry/otel/OpenTelemetryTelemetry.kt:106`
- **Problem:** onScreenRendered/onActionDispatched synthesize spans from the supplied durationMs by back-dating startTimestamp (lines 106-116, 143-153). These are always root INTERNAL spans — there is no server->client trace-context propagation, so a screen render on the client cannot be correlated to the /screens/{id} server span that produced it, and the back-dated timestamps will not align cleanly with sampled traces. The metrics (histograms/counters) are sound; the tracing value is limited to standalone latency, not distributed traces.
- **Fix:** If distributed tracing is a goal, propagate W3C traceparent from the server response into the client telemetry so client spans become children of the server span; otherwise document that these are latency-only spans to set operator expectations.
