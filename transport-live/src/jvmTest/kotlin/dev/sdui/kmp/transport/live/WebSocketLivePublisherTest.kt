package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.StatePath
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pure-unit coverage of [WebSocketLivePublisher]: register / broadcast / unregister and
 * the failure-removal contract, exercised against a recording fake [WebSocketSession]
 * that captures sent frames into a list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketLivePublisherTest {

    @Test
    fun broadcast_delivers_to_registered_session() = runTest {
        val publisher = WebSocketLivePublisher()
        val session = RecordingSession()
        publisher.register("home", session)
        assertEquals(1, publisher.subscriberCount("home"))

        val event = LiveEvent.StateUpdate(
            updates = mapOf(StatePath("ticker") to JsonPrimitive(7)),
        )
        publisher.broadcast("home", event)

        val sent = session.takeOutgoingTexts()
        assertEquals(1, sent.size, "expected exactly one frame, got $sent")
        val decoded = SduiJson.decodeFromString(LiveEvent.serializer(), sent.single())
        assertEquals(event, decoded)
    }

    @Test
    fun unregister_stops_delivery() = runTest {
        val publisher = WebSocketLivePublisher()
        val session = RecordingSession()
        publisher.register("home", session)
        publisher.unregister("home", session)

        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("k") to JsonPrimitive(1))),
        )

        assertTrue(session.takeOutgoingTexts().isEmpty())
        assertEquals(0, publisher.subscriberCount("home"))
    }

    @Test
    fun broadcast_isolates_topics() = runTest {
        val publisher = WebSocketLivePublisher()
        val homeA = RecordingSession()
        val homeB = RecordingSession()
        val other = RecordingSession()
        publisher.register("home", homeA)
        publisher.register("home", homeB)
        publisher.register("about", other)

        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("x") to JsonPrimitive(1))),
        )

        assertEquals(1, homeA.takeOutgoingTexts().size)
        assertEquals(1, homeB.takeOutgoingTexts().size)
        assertTrue(other.takeOutgoingTexts().isEmpty())
    }

    @Test
    fun failed_send_evicts_session() = runTest {
        val publisher = WebSocketLivePublisher()
        val ok = RecordingSession()
        val broken = RecordingSession(failOnSend = true)
        publisher.register("home", ok)
        publisher.register("home", broken)
        assertEquals(2, publisher.subscriberCount("home"))

        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("x") to JsonPrimitive(1))),
        )

        // Broken session was reaped; surviving subscriber count is 1.
        assertEquals(1, publisher.subscriberCount("home"))
        // Healthy session received the frame.
        assertEquals(1, ok.takeOutgoingTexts().size)

        // A second broadcast goes only to the healthy session.
        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("y") to JsonPrimitive(2))),
        )
        assertEquals(1, ok.takeOutgoingTexts().size)
    }

    @Test
    fun register_is_idempotent() = runTest {
        val publisher = WebSocketLivePublisher()
        val session = RecordingSession()
        publisher.register("home", session)
        publisher.register("home", session)
        assertEquals(1, publisher.subscriberCount("home"))

        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("x") to JsonPrimitive(1))),
        )
        assertEquals(1, session.takeOutgoingTexts().size)
    }

    @Test
    fun first_and_last_subscriber_hooks_fire_on_topic_transitions() = runTest {
        val firsts = mutableListOf<String>()
        val lasts = mutableListOf<String>()
        val publisher = WebSocketLivePublisher(
            onFirstSubscribe = { firsts += it },
            onLastUnsubscribe = { lasts += it },
        )
        val a = RecordingSession()
        val b = RecordingSession()

        publisher.register("home", a) // 0 -> 1: first-subscribe fires.
        publisher.register("home", b) // 1 -> 2: no transition.
        assertEquals(listOf("home"), firsts)
        assertTrue(lasts.isEmpty())

        publisher.unregister("home", a) // 2 -> 1: no transition.
        assertEquals(1, publisher.subscriberCount("home"))
        assertTrue(lasts.isEmpty())

        publisher.unregister("home", b) // 1 -> 0: last-unsubscribe fires + topic evicted.
        assertEquals(listOf("home"), lasts)
        assertEquals(0, publisher.subscriberCount("home"))

        // Re-subscribing after eviction is a fresh 0 -> 1 transition, so first fires again.
        publisher.register("home", a)
        assertEquals(listOf("home", "home"), firsts)
    }

    @Test
    fun slow_session_does_not_block_delivery_to_other_sessions() = runTest {
        val timeout = 2.seconds
        val publisher = WebSocketLivePublisher(sendTimeout = timeout)
        // Registered first, so a sequential fan-out would deliver to it before `healthy`.
        val stalled = RecordingSession(blockOnSend = true)
        val healthy = RecordingSession()
        publisher.register("home", stalled)
        publisher.register("home", healthy)

        val job = launch {
            publisher.broadcast(
                "home",
                LiveEvent.StateUpdate(updates = mapOf(StatePath("x") to JsonPrimitive(1))),
            )
        }

        // Half-way through the stalled peer's timeout the healthy peer already has its frame —
        // proof the fan-out is concurrent, not queued behind the stall.
        advanceTimeBy(timeout / 2)
        runCurrent()
        assertEquals(
            1,
            healthy.takeOutgoingTexts().size,
            "healthy session must receive without waiting on the stalled peer",
        )
        assertEquals(2, publisher.subscriberCount("home"), "stalled peer not yet dropped mid-timeout")

        // Let the stalled peer's send exceed the timeout: it is closed and reaped.
        advanceUntilIdle()
        job.join()
        // Exactly one subscriber remains: the stalled peer was reaped, the healthy one survived.
        assertEquals(1, publisher.subscriberCount("home"), "stalled peer dropped after timeout")
        assertTrue(stalled.wasClosed, "a timed-out session must be closed")

        // A follow-up broadcast still reaches the surviving healthy session.
        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("y") to JsonPrimitive(2))),
        )
        assertEquals(1, healthy.takeOutgoingTexts().size, "healthy session keeps receiving")
    }

    @Test
    fun timed_out_last_session_is_dropped_and_evicts_topic() = runTest {
        val lasts = mutableListOf<String>()
        val timeout = 1.seconds
        val publisher = WebSocketLivePublisher(
            onLastUnsubscribe = { lasts += it },
            sendTimeout = timeout,
        )
        val stalled = RecordingSession(blockOnSend = true)
        publisher.register("home", stalled)

        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("x") to JsonPrimitive(1))),
        )

        assertEquals(0, publisher.subscriberCount("home"), "stalled last peer dropped")
        assertEquals(listOf("home"), lasts, "dropping the last subscriber evicts the topic")
        assertTrue(stalled.wasClosed, "a timed-out session must be closed")
    }

    @Test
    fun close_all_closes_every_session_and_clears_the_registry() = runTest {
        val publisher = WebSocketLivePublisher()
        val home = RecordingSession()
        val aboutA = RecordingSession()
        val aboutB = RecordingSession()
        publisher.register("home", home)
        publisher.register("about", aboutA)
        publisher.register("about", aboutB)

        publisher.closeAll()

        assertTrue(home.wasClosed, "home session must be closed on drain")
        assertTrue(aboutA.wasClosed, "about session A must be closed on drain")
        assertTrue(aboutB.wasClosed, "about session B must be closed on drain")
        assertEquals(0, publisher.subscriberCount("home"), "registry must be cleared after drain")
        assertEquals(0, publisher.subscriberCount("about"), "registry must be cleared after drain")

        // Idempotent: a second drain on an empty registry is a no-op.
        publisher.closeAll()
        assertEquals(0, publisher.subscriberCount("home"))
    }

    @Test
    fun reaping_last_dead_peer_fires_last_unsubscribe_and_evicts() = runTest {
        val lasts = mutableListOf<String>()
        val publisher = WebSocketLivePublisher(onLastUnsubscribe = { lasts += it })
        val broken = RecordingSession(failOnSend = true)
        publisher.register("home", broken)

        publisher.broadcast(
            "home",
            LiveEvent.StateUpdate(updates = mapOf(StatePath("x") to JsonPrimitive(1))),
        )

        assertEquals(listOf("home"), lasts, "reaping the last subscriber must fire the hook")
        assertEquals(0, publisher.subscriberCount("home"))
    }
}

/**
 * Minimal in-memory [WebSocketSession] that records every outgoing [Frame.Text]'s payload
 * via its `send` override. Optionally fails on send ([failOnSend]) or blocks a text send
 * forever ([blockOnSend]) to model a stalled peer; a [Frame.Close] is always accepted and
 * flips [wasClosed] so tests can assert a dropped session was closed. The publisher only ever
 * calls `session.send(...)` / `session.close(...)`, so we delegate `outgoing` to a never-used
 * real [Channel] rather than re-implementing the whole [SendChannel] contract.
 */
private class RecordingSession(
    private val failOnSend: Boolean = false,
    private val blockOnSend: Boolean = false,
) : WebSocketSession {

    /** Set to `true` once a [Frame.Close] has been sent through this session. */
    var wasClosed: Boolean = false
        private set

    private val supervisor = SupervisorJob()

    @Suppress("InjectDispatcher")
    override val coroutineContext: CoroutineContext = Dispatchers.Unconfined + supervisor

    private val sentTexts = mutableListOf<String>()

    @Suppress("OVERRIDE_DEPRECATION")
    override var masking: Boolean = false

    @Suppress("OVERRIDE_DEPRECATION")
    override var maxFrameSize: Long = Long.MAX_VALUE

    override val extensions: List<WebSocketExtension<*>> = emptyList()

    override val incoming: ReceiveChannel<Frame> = Channel(Channel.RENDEZVOUS)

    // Real channel — never used by [WebSocketLivePublisher] (it calls [send]) but the
    // interface forces a non-null override.
    override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)

    override suspend fun flush() {
        // No-op: nothing is buffered; sentTexts is appended synchronously in send().
    }

    @Deprecated(
        "Use cancel() instead.",
        replaceWith = ReplaceWith("cancel()"),
    )
    override fun terminate() {
        supervisor.cancel()
    }

    override suspend fun send(frame: Frame) {
        // A Close frame is always accepted so the publisher's best-effort close can complete
        // even on an otherwise-stalled peer; record it for drop assertions.
        if (frame is Frame.Close) {
            wasClosed = true
            return
        }
        if (failOnSend) error("simulated send failure")
        if (blockOnSend) awaitCancellation()
        if (frame is Frame.Text) sentTexts.add(frame.readText())
    }

    fun takeOutgoingTexts(): List<String> {
        val copy = sentTexts.toList()
        sentTexts.clear()
        return copy
    }
}
