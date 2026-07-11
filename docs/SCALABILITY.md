# Horizontal Scalability — Deep Dive & Remediation Plan

> Companion to [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md). Scope: the live-update path
> (studio publish → every connected client, across many server instances). This is the audit's
> **grade D** area and the single biggest gap between "works on my laptop" and "runs a fleet."

## Implementation status (updated 2026-07-11)

Phase 2 landed in commit `004c10b`. Multi-instance live updates now work:

- ✅ **Cross-JVM publish** — publish flows through a shared `LiveBus`; each instance's
  `DynamicLiveBusBridge` fans out to its own sockets. Regression test proves A→bus→B delivery.
- ✅ **Dynamic topic bridging + registry eviction** — reference-counted, atomic empty-topic eviction.
- ✅ **Non-blocking broadcast fan-out** — concurrent, per-send timeout, dead-peer reaping.
- ✅ **Client reconnect + resync** — capped backoff + jitter, resync hook, idempotent start/clean stop.
- ✅ **`listScreens` projection** — no longer scans every version body.

**Remaining follow-ups** (not blockers; tracked for later hardening):
- Reconnect backoff should not reset on a server that accepts-then-immediately-closes (flap
  protection); resync could fire after a *successful* connect rather than before every attempt.
- `PostgresLivePublisher` created in `Main` is `Closeable` but not closed on shutdown (ties into
  the Wave 3 graceful-shutdown work).
- `sample-server` still uses eager `bridgeAllTopics`; convert to on-demand `DynamicLiveBusBridge`.
- A true two-JVM Testcontainers-Postgres integration test can layer on the deterministic
  in-process regression already added.
- Sharded/per-topic Postgres channels + a dropped-event metric (Phase 2C #6) for hot-topic scale.

## TL;DR

Live updates are **correct on a single JVM and silent across JVMs.** The building blocks for
horizontal fan-out exist and are well-factored (`LiveBus`, `PostgresLivePublisher`,
`LiveBusBridge`), but the publish path never calls into them, and the client never survives a
dropped socket. Three code changes make multi-instance live updates actually work; three more
make them scale under load.

## Current data flow (what actually happens today)

```
studio publish ──► WebSocketPublishNotifier.screenPublished()
                        │
                        └─► publisher.broadcast(screenId, event)   ← LOCAL registry only
                                    │
                                    ▼
                        WS sessions connected to THIS JVM
```

`LiveBus.publish()` is never called on the publish path, so no `NOTIFY sdui_live` is emitted.
A client connected to instance **B** never hears about a publish that landed on instance **A**.
Behind any load balancer (i.e. every real deployment), most clients miss most updates.

Verified: `LiveBus.publish()` has **zero production callers**
(`studio-server/.../routes/WebSocketPublishNotifier.kt:33` broadcasts locally;
`transport-live/.../LiveBusBridge.kt` is the consumer half but nothing feeds the bus on publish).

## Target data flow (multi-instance correct)

```
studio publish ──► WebSocketPublishNotifier ──► LiveBus.publish(topic, event)
                                                       │  (Postgres NOTIFY / Redis / NATS)
                        ┌──────────────────────────────┼──────────────────────────────┐
                        ▼                               ▼                               ▼
                  JVM A: bridgeTopic              JVM B: bridgeTopic              JVM C: bridgeTopic
                        │                               │                               │
                        ▼                               ▼                               ▼
                  local WS sessions               local WS sessions               local WS sessions
```

Every instance (including the publisher's own) receives the event from the bus via its
`LiveBusBridge` and fans it out to *its* local WebSocket registry. The publisher stops
broadcasting locally and instead publishes to the bus, so there is exactly one delivery path
and no double-send.

## Remediation — ordered

### Phase 2A — make cross-JVM delivery work (correctness)

| # | Change | File | Design |
|---|--------|------|--------|
| 1 | **Publish through the bus, not the local registry.** Inject a `LiveBus` into `WebSocketPublishNotifier`; replace `publisher.broadcast(...)` with `bus.publish(screenId, event)`. Local delivery then arrives via this JVM's bridge (Postgres delivers `NOTIFY` to the emitting session too; the in-process bus emits to all subscribers). | `studio-server/.../routes/WebSocketPublishNotifier.kt:33` | Keep the `PublishNotifier` "must not throw" contract: wrap `publish` in `runCatching`. Wire `PostgresLivePublisher` in prod, `InProcessLiveBus` in dev/test. |
| 2 | **Bridge topics dynamically.** Call `bridgeTopic(bus, publisher, topic, scope)` from the WS route's `register` callback when the first local client subscribes to a topic; cancel the returned `Job` when the last one leaves. Replaces the hard-coded `bridgeAllTopics(...)` list in `Main.kt`. | `transport-live/.../LiveBusBridge.kt`, `studio-server/.../Main.kt:265`, `WebSocketLivePublisher.kt:54` | Reference-count subscribers per topic; the same counter fixes the unbounded-registry leak (evict topic + cancel bridge at zero). |
| 3 | **Two-JVM integration test.** Boot two `studioModule`s sharing one `PostgresLivePublisher` (Testcontainers Postgres); publish on A, assert a client on B receives the `TreePatchEvent`. This is the regression that proves #1/#2 and must gate future changes. | new test in `transport-live/src/jvmTest` | Already have `FakePostgresDataSource` + `PostgresLivePublisherTest` to build on. |

### Phase 2B — client survives the network (correctness)

| # | Change | File | Design |
|---|--------|------|--------|
| 4 | **Reconnect with backoff.** `runSession()` opens one socket and completes; `start()`'s `if (pump != null) return` then blocks restart. Wrap the session in a capped exponential backoff + jitter loop that re-establishes on any close. | `transport-live/.../WebSocketLiveSource.kt:74` | On every (re)connect, first HTTP-refetch the canonical screen, THEN stream — closes the no-replay gap (the bus has no history by design; see `LiveBus` KDoc). |

Without #4, every rolling deploy, LB idle-timeout (typically 60 s), or transient blip permanently
silences a client until the app process is killed. This is guaranteed to bite in production
during the first deploy after go-live.

### Phase 2C — scale under load (throughput & memory)

| # | Change | File | Design |
|---|--------|------|--------|
| 5 | **Non-blocking broadcast fan-out.** `broadcast` currently `send()`s to each session sequentially with no timeout — one stalled TCP peer stalls delivery to everyone after it on a hot topic. | `WebSocketLivePublisher.kt:73` | Fan out concurrently (`sessions.map { async { withTimeout(T) { it.send(...) } } }`); give each session a bounded drop-oldest outbound channel so a slow client is dropped, not amplified. |
| 6 | **Per-topic / sharded bus channels + metrics.** A single Postgres channel with a 64-slot shared buffer multiplexes all topics; a burst on one hot topic silently evicts unrelated events, and every JVM decodes every event. | `PostgresLivePublisher.kt:96` | Shard by topic hash (or move to Redis/NATS for real fan-out scale); enlarge/per-topic buffer; emit a `live_events_dropped_total` counter so eviction is observable, not silent. |

### Adjacent scale blocker (data plane, not live)

- **`listScreens` full-history heap scan** — `ScreenStore.kt:59` does `ScreenVersions.selectAll()`,
  materializing every `body_json` TEXT of every version ever authored, just to map a UUID → version
  number. Over a 5–10-year history this trends toward OOM / pool starvation. Fix: project only
  `(id, versionNumber)` or resolve via a join on `current_version_id`. Independent of the live path
  but on the same "degrades with age/scale" axis.

## What is already right (don't regress it)

- `LiveBus` is a clean seam: in-process for tests, Postgres for prod, Redis/NATS drop-in for hosts
  that run them — no changes to `WebSocketLivePublisher` or the route surface.
- `LiveBusBridge` keeps tier hygiene: the WS publisher stays a pure fan-out registry with no
  pub/sub knowledge.
- Cache layer (`transport-cache`) uses per-topic mutexes, non-blocking `DROP_OLDEST`, and atomic
  disk writes — good hygiene to preserve.
- Deliberate no-history semantics + "HTTP-fetch then stream" catch-up model is the correct design
  for a live bus; #4 just needs to actually implement the catch-up on reconnect.

## Definition of done for "horizontally scalable"

1. Publish on instance A reaches a client on instance B (Phase 2A #3 test is green).
2. A client survives an instance restart / LB idle-timeout and re-syncs without a manual reload
   (Phase 2B).
3. One stalled subscriber cannot delay delivery to others; a slow client is dropped with a metric,
   not buffered unboundedly (Phase 2C #5).
4. Dropped live events and per-topic subscriber counts are observable in Prometheus (Phase 2C #6).
5. Admin/data-plane queries do not scan full version history (`listScreens` fix).
