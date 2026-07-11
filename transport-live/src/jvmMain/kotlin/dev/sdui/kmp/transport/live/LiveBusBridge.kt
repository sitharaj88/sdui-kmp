package dev.sdui.kmp.transport.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Wire a [LiveBus] subscription to a [WebSocketLivePublisher] so events published by **any**
 * JVM in the cluster fan out to every connected WebSocket session in **this** JVM.
 *
 * Pattern: each server module owns one local [WebSocketLivePublisher] (the in-process WS
 * registry) plus a shared [LiveBus] (in-process for tests, [PostgresLivePublisher] for
 * production). On each topic of interest, this helper launches a coroutine in [scope] that
 * forwards every [bus] event into [publisher]'s broadcast.
 *
 * The bridge is lazy — callers request a bridge for a specific topic when a WebSocket
 * client first subscribes to it, and cancel the returned [Job] when the last subscriber
 * disconnects. For the typical "subscribe to every published topic" sample-server wiring,
 * call [bridgeAllTopics] from the host's startup block.
 *
 * **Why not bake this into [WebSocketLivePublisher]?** Tier hygiene: the publisher is a
 * pure WebSocket fan-out registry — no knowledge of pub/sub mechanics. Adding a [LiveBus]
 * field would force every existing caller (and every future bus implementation: Redis,
 * NATS, Kafka) to thread an extra constructor arg. The bridge is a thin adapter so the
 * two halves stay independently testable.
 */
public fun bridgeTopic(
    bus: LiveBus,
    publisher: WebSocketLivePublisher,
    topic: String,
    scope: CoroutineScope,
): Job = scope.launch {
    bus.subscribe(topic).collect { event ->
        publisher.broadcast(topic, event)
    }
}

/**
 * Bridge a fixed set of topics. Returns a single [Job] that cancels every per-topic forwarder
 * when cancelled.
 *
 * Use when the host owns a known list of screen ids (sample-server: `home`, `about`, …).
 * For dynamic topic discovery, call [bridgeTopic] on demand from the WebSocket route's
 * `register` callback instead.
 */
public fun bridgeAllTopics(
    bus: LiveBus,
    publisher: WebSocketLivePublisher,
    topics: Iterable<String>,
    scope: CoroutineScope,
): Job = scope.launch {
    val children = topics.map { topic ->
        launch { bus.subscribe(topic).collect { event -> publisher.broadcast(topic, event) } }
    }

    @Suppress("UNUSED_VARIABLE")
    val keepAlive = children
}

/**
 * On-demand [LiveBus] -> [WebSocketLivePublisher] bridge that starts forwarding a topic only
 * when the first local WebSocket client subscribes to it and cancels that forwarder when the
 * last one leaves. This is the dynamic counterpart to [bridgeAllTopics]: instead of eagerly
 * subscribing to a hard-coded topic list, it grows and shrinks with the live subscriber set,
 * which keeps the bus subscription count (and the WebSocket registry) bounded to topics that
 * actually have viewers.
 *
 * The bridge owns and exposes a [publisher] pre-wired to call [WebSocketLivePublisher.register]
 * / [WebSocketLivePublisher.unregister]'s first/last-subscriber hooks. Hand [publisher] to
 * [installLiveScreensRoute] (or any fan-out route) and no further wiring is required — a client
 * opening `ws://host/live/screens/home` triggers the `home` bridge, and its disconnect tears it
 * down.
 *
 * Publish side stays decoupled: the studio's publish path calls [LiveBus.publish]; every
 * instance that has a local subscriber for that topic already has an active bridge and fans the
 * event out to its own sockets. An instance with no local subscriber simply has no bridge and
 * drops the event — correct, because it has no one to deliver to.
 *
 * @param bus the shared cross-process bus (in-process for tests, Postgres for production).
 * @param scope coroutine scope owning the per-topic forwarder jobs; cancel it to stop every
 *   bridge (typically the host [io.ktor.server.application.Application]'s scope).
 */
public class DynamicLiveBusBridge(
    private val bus: LiveBus,
    private val scope: CoroutineScope,
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Fan-out registry to install on the live WebSocket route. Its first/last-subscriber
     * hooks drive [onFirstSubscribe] / [onLastUnsubscribe] on this bridge.
     */
    public val publisher: WebSocketLivePublisher = WebSocketLivePublisher(
        onFirstSubscribe = ::onFirstSubscribe,
        onLastUnsubscribe = ::onLastUnsubscribe,
    )

    /** Start (or reuse) the forwarder for [topic]. Idempotent — a live forwarder is kept. */
    private fun onFirstSubscribe(topic: String) {
        jobs.compute(topic) { _, existing ->
            existing?.takeIf { it.isActive } ?: bridgeTopic(bus, publisher, topic, scope)
        }
    }

    /** Cancel the forwarder for [topic] once its last local subscriber has gone. */
    private fun onLastUnsubscribe(topic: String) {
        jobs.remove(topic)?.cancel()
    }
}
