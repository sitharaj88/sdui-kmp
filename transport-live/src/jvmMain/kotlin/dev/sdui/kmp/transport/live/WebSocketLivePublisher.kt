package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.SduiJson
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Server-side fan-out hub for [LiveEvent]s, the JVM publisher counterpart to the
 * client-side [WebSocketLiveSource].
 *
 * Connections are organised by **topic** — typically a screen id. A session that subscribes
 * to a topic via [register] receives every [LiveEvent] subsequently broadcast to that topic
 * via [broadcast]. Sessions are deregistered automatically when [broadcast] fails to send
 * (the WebSocket is closed or the peer is gone), and can be removed explicitly via
 * [unregister] when a route handler observes a clean disconnect.
 *
 * This class is thread-safe. The per-topic registry is a [ConcurrentHashMap] whose per-key
 * mutations (add / remove / snapshot / evict) all run inside [ConcurrentHashMap.compute] /
 * [ConcurrentHashMap.computeIfPresent] blocks. The map's per-bin lock serialises the
 * check-empty-then-evict decision against a concurrent add, so a topic can never be evicted
 * out from under a session that is registering at the same instant — the race a separate
 * [kotlinx.coroutines.sync.Mutex] plus `computeIfAbsent` could not close. Fan-out `send`s
 * happen **outside** the lock so a slow peer never blocks register/unregister on the topic.
 *
 * **Reference-counted eviction.** A topic's [TopicState] is removed from the registry the
 * moment its last subscriber leaves (via [unregister] or reaped by a failed [broadcast]).
 * This bounds the registry to topics with live subscribers rather than leaking a growing set
 * of empty entries over the process lifetime. The [onFirstSubscribe] / [onLastUnsubscribe]
 * hooks fire on the `0 -> 1` and `1 -> 0` transitions so a host can lazily wire (and tear
 * down) a per-topic [LiveBus] bridge — see [DynamicLiveBusBridge]. The hooks run inside the
 * per-key lock so the transition and the host's reaction cannot interleave; keep them cheap
 * and non-suspending (scheduling a coroutine or cancelling a [kotlinx.coroutines.Job] is fine,
 * blocking I/O is not).
 *
 * Intentionally JVM-only: Ktor's server WebSocket APIs are not available on Native or
 * Wasm targets. Clients on every platform consume the same wire format via
 * [WebSocketLiveSource].
 *
 * **Non-blocking fan-out.** [broadcast] sends to every subscribed session **concurrently**,
 * each send bounded by [sendTimeout]. A single stalled TCP peer can therefore never delay
 * delivery to the other subscribers on a hot topic — the classic head-of-line block of a
 * sequential loop. A session whose send times out or throws is closed and reaped from the
 * registry, so a slow client is dropped rather than allowed to back up delivery indefinitely.
 *
 * @param onFirstSubscribe invoked with the topic when it gains its first subscriber
 *   (`0 -> 1`). Defaults to a no-op so plain fan-out hosts need no wiring.
 * @param onLastUnsubscribe invoked with the topic when it loses its last subscriber
 *   (`1 -> 0`), immediately before the empty [TopicState] is evicted. Defaults to a no-op.
 * @param sendTimeout per-session ceiling for a single [broadcast] send. A session that does
 *   not accept the frame within this window is treated as stalled: it is closed and reaped.
 *   Defaults to [DEFAULT_SEND_TIMEOUT].
 */
public class WebSocketLivePublisher(
    private val onFirstSubscribe: (topic: String) -> Unit = {},
    private val onLastUnsubscribe: (topic: String) -> Unit = {},
    private val sendTimeout: Duration = DEFAULT_SEND_TIMEOUT,
) {

    public companion object {
        /**
         * Default per-session send ceiling used when none is supplied. Long enough to absorb
         * an ordinary network hiccup, short enough that a wedged peer is evicted promptly
         * instead of holding a slot on a hot topic.
         */
        public val DEFAULT_SEND_TIMEOUT: Duration = 5.seconds
    }

    /** Per-topic set of subscribed sessions. Guarded by the [ConcurrentHashMap] per-key lock. */
    private data class TopicState(
        val sessions: MutableSet<WebSocketSession> = linkedSetOf(),
    )

    private val topics = ConcurrentHashMap<String, TopicState>()

    /**
     * Subscribe [session] to [topic]. Idempotent — re-registering the same session is a
     * no-op so route handlers can call this without bookkeeping. Fires [onFirstSubscribe]
     * when this call takes the topic from zero to one subscriber.
     */
    public suspend fun register(topic: String, session: WebSocketSession) {
        topics.compute(topic) { _, existing ->
            val state = existing ?: TopicState()
            val wasEmpty = state.sessions.isEmpty()
            state.sessions.add(session)
            if (wasEmpty) onFirstSubscribe(topic)
            state
        }
    }

    /**
     * Unsubscribe [session] from [topic]. Tolerates already-removed sessions and unknown
     * topics so the close path can call this without checks. Fires [onLastUnsubscribe] and
     * evicts the topic when this call removes its last subscriber.
     */
    public suspend fun unregister(topic: String, session: WebSocketSession) {
        topics.computeIfPresent(topic) { _, state ->
            state.sessions.remove(session)
            evictIfEmpty(topic, state)
        }
    }

    /**
     * Broadcast [event] as a single text frame to every session currently subscribed to
     * [topic]. Sends run **concurrently**, each bounded by [sendTimeout], so one stalled peer
     * cannot delay delivery to the others. Sessions whose send times out or throws are closed
     * and removed from the topic before the next broadcast — this is the only mechanism by
     * which a dead or wedged peer is reaped, so callers never need to poll for liveness.
     * Reaping the last subscriber evicts the topic and fires [onLastUnsubscribe], exactly like
     * an explicit [unregister].
     */
    public suspend fun broadcast(topic: String, event: LiveEvent) {
        val payload = SduiJson.encodeToString(LiveEvent.serializer(), event)
        // Snapshot under the per-key lock so a concurrent register/unregister can't mutate
        // the set while we copy it. Sends happen outside the lock so a slow peer doesn't
        // block other subscribers from being added or removed.
        var snapshot: List<WebSocketSession> = emptyList()
        topics.computeIfPresent(topic) { _, state ->
            snapshot = state.sessions.toList()
            state
        }
        if (snapshot.isEmpty()) return
        // Fan out concurrently. Each child returns the session iff its send failed or timed
        // out; healthy sends return null. coroutineScope joins all children before returning.
        val failed = coroutineScope {
            snapshot.map { session ->
                async { sendOrDrop(session, payload) }
            }.awaitAll()
        }.filterNotNull()
        if (failed.isNotEmpty()) {
            val failedSet = failed.toSet()
            topics.computeIfPresent(topic) { _, state ->
                state.sessions.removeAll(failedSet)
                evictIfEmpty(topic, state)
            }
        }
    }

    /**
     * Send [payload] to [session] under [sendTimeout], returning `null` on success or the
     * [session] itself when it should be reaped. A reaped session is best-effort closed so a
     * wedged peer's socket is released rather than leaked; the close is itself time-bounded so
     * it cannot re-stall the broadcast.
     */
    private suspend fun sendOrDrop(session: WebSocketSession, payload: String): WebSocketSession? {
        val dropped = try {
            withTimeout(sendTimeout) { session.send(Frame.Text(payload)) }
            false
        } catch (timeout: TimeoutCancellationException) {
            // The peer did not accept the frame in time — treat it as stalled and drop it.
            @Suppress("SwallowedException", "UNUSED_VARIABLE")
            val ignored = timeout
            true
        } catch (cancel: CancellationException) {
            // Real cancellation of the surrounding scope — propagate, don't reap.
            throw cancel
        } catch (e: InterruptedException) {
            // Restore the interrupt flag so the caller's coroutine sees cancellation;
            // the broken session is reaped just like any other failed peer.
            Thread.currentThread().interrupt()
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            // Any other send failure (closed channel, peer gone, IO error) drops the
            // session. Logging is the host's job; the publisher stays silent so a noisy
            // network does not pollute server logs.
            @Suppress("SwallowedException", "UNUSED_VARIABLE")
            val ignored = t
            true
        }
        if (!dropped) return null
        // Best-effort, time-bounded close so a wedged peer cannot re-stall us here.
        withTimeoutOrNull(sendTimeout) {
            try {
                session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "slow-consumer"))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                @Suppress("SwallowedException", "UNUSED_VARIABLE")
                val ignored = t
            }
        }
        return session
    }

    /**
     * Return [state] when it still has subscribers, or `null` (which removes the entry from
     * [topics]) after firing [onLastUnsubscribe] when it has just gone empty. Must be called
     * from inside a [ConcurrentHashMap.compute] / [ConcurrentHashMap.computeIfPresent] block
     * so the transition and eviction are atomic with respect to a concurrent [register].
     */
    private fun evictIfEmpty(topic: String, state: TopicState): TopicState? =
        if (state.sessions.isEmpty()) {
            onLastUnsubscribe(topic)
            null
        } else {
            state
        }

    /**
     * Close every subscribed session across all topics and clear the registry. Intended for a
     * host's graceful-shutdown path (`ApplicationStopping`) so live sockets are drained with a
     * clean close frame rather than severed when the engine stops. Each close is bounded by
     * [sendTimeout] so a wedged peer cannot stall teardown, and failures are ignored — the
     * process is going away regardless. Idempotent: a second call after the registry is empty
     * is a no-op.
     *
     * @param reason the WebSocket close reason sent to each session. Defaults to
     *   [CloseReason.Codes.GOING_AWAY] with a `server-shutdown` message.
     */
    public suspend fun closeAll(
        reason: CloseReason = CloseReason(CloseReason.Codes.GOING_AWAY, "server-shutdown"),
    ) {
        // Atomically remove each topic and snapshot its sessions under the per-key lock so a
        // concurrent register/broadcast sees the now-empty registry; the socket close itself
        // runs outside the lock so a slow peer never blocks the drain of the others.
        val drained = ArrayList<WebSocketSession>()
        for (topic in topics.keys.toList()) {
            topics.remove(topic)?.let { drained.addAll(it.sessions) }
        }
        for (session in drained) {
            withTimeoutOrNull(sendTimeout) {
                try {
                    session.close(reason)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    @Suppress("SwallowedException", "UNUSED_VARIABLE")
                    val ignored = t
                }
            }
        }
    }

    /** Diagnostic accessor — current subscriber count for [topic]. */
    public fun subscriberCount(topic: String): Int = topics[topic]?.sessions?.size ?: 0
}
