package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import kotlinx.coroutines.flow.Flow

/**
 * Pluggable pub/sub bus that transports [LiveEvent]s between the publisher (the studio)
 * and one or more fan-out servers (sample-server, regional edge servers).
 *
 * Two implementations ship today:
 *
 * - [InProcessLiveBus] — a [kotlinx.coroutines.flow.MutableSharedFlow] inside a single JVM.
 *   Used by tests and the dev wiring where the studio + sample-server run as one process.
 * - [PostgresLivePublisher] — Postgres `LISTEN` / `NOTIFY` so a publish from one JVM fans
 *   out to subscribers in every other JVM connected to the same database.
 *
 * Hosts that already operate Redis, NATS, or Kafka can drop in their own implementation
 * without touching the [WebSocketLivePublisher] or [installLiveScreensRoute] surfaces.
 *
 * Contract:
 *
 * - [publish] returns once the event is durably handed to the underlying transport (for
 *   Postgres, that's after `NOTIFY` returns; for in-process, after the SharedFlow emit).
 *   Publishers do **not** wait for subscriber delivery — fan-out is best-effort fire-and-
 *   forget; the in-process bus drops slowest subscriber on overflow, the Postgres bus
 *   relies on the receiving connection's queue size.
 * - [subscribe] returns a cold [Flow] that emits every event published to [topic] from the
 *   moment of subscription onward. **Late subscribers do not see history** — by design, a
 *   live bus is not an event store. Catch-up is the caller's job (typically: HTTP fetch
 *   the canonical screen body, then live-stream from now).
 */
public interface LiveBus {

    /** Fire-and-forget publish [event] to [topic]. Returns when the underlying transport accepted the message. */
    public suspend fun publish(topic: String, event: LiveEvent)

    /** Subscribe to [topic]. Cold flow — no history replay. */
    public fun subscribe(topic: String): Flow<LiveEvent>
}
