package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.StatePath
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconnect-loop coverage for [WebSocketLiveSource]: the source re-establishes after a dropped
 * socket with observable backoff, delivers a resync signal on every (re)connect, surfaces
 * connection failures, and stops cleanly without further reconnects. Exercised against a fake
 * [WebSocketSession] whose inbound channel the test drives directly, with injected delay and
 * jitter seams so timing is deterministic.
 *
 * The reconnect loop necessarily runs in [backgroundScope] — it is an unbounded loop that would
 * otherwise keep [runTest] from ever completing. Work launched in [backgroundScope] is driven by
 * [runCurrent] (which runs everything scheduled at the current virtual instant), not by
 * `advanceUntilIdle`, which by design stops as soon as no foreground work remains and so never
 * dispatches background-only tasks. Because the injected `delayFn` records rather than suspends,
 * no virtual time ever needs advancing, so [runCurrent] alone deterministically drains each step.
 */
class WebSocketLiveSourceReconnectTest {

    private fun stateEvent(key: String, value: Int): LiveEvent =
        LiveEvent.StateUpdate(updates = mapOf(StatePath(key) to JsonPrimitive(value)))

    private fun encode(event: LiveEvent): String =
        SduiJson.encodeToString(LiveEvent.serializer(), event)

    @Test
    fun reconnects_after_close_resumes_emitting_and_signals_resync() = runTest {
        val sessions = mutableListOf<FakeLiveSocket>()
        val delays = mutableListOf<Long>()
        val received = mutableListOf<LiveEvent>()
        var resyncCount = 0

        val source = WebSocketLiveSource(
            onParseError = { _, _ -> },
            onReconnect = { resyncCount += 1 },
            onConnectionError = { },
            reconnect = ReconnectPolicy(),
            scope = backgroundScope,
            connect = { FakeLiveSocket().also { sessions += it } },
            delayFn = { delays += it },
            random = { 0.0 },
        )
        backgroundScope.launch { source.events.collect { received += it } }

        source.start()
        runCurrent()
        assertEquals(1, sessions.size, "first connection established")
        assertEquals(1, resyncCount, "resync fires before the first stream too")

        val first = stateEvent("a", 1)
        sessions[0].deliver(encode(first))
        runCurrent()
        assertEquals(listOf(first), received)

        // Underlying socket drops: the loop must back off and reconnect on its own.
        sessions[0].dropConnection()
        runCurrent()
        assertEquals(2, sessions.size, "source reconnected after the socket closed")
        assertEquals(listOf(500L), delays, "one backoff delay observed before reconnect")
        assertEquals(2, resyncCount, "resync signal delivered on reconnect")

        // Streaming resumes on the fresh socket.
        val second = stateEvent("b", 2)
        sessions[1].deliver(encode(second))
        runCurrent()
        assertEquals(listOf(first, second), received)

        source.stop()
    }

    @Test
    fun repeated_connect_failures_back_off_exponentially_and_are_reported() = runTest {
        val delays = mutableListOf<Long>()
        val errors = mutableListOf<Throwable>()
        var connectCalls = 0

        val source = WebSocketLiveSource(
            onParseError = { _, _ -> },
            onReconnect = { },
            onConnectionError = { errors += it },
            reconnect = ReconnectPolicy(),
            scope = backgroundScope,
            connect = {
                connectCalls += 1
                if (connectCalls <= 3) throw IOException("endpoint down") else FakeLiveSocket()
            },
            delayFn = { delays += it },
            random = { 0.0 },
        )

        source.start()
        runCurrent()

        assertEquals(listOf(500L, 1_000L, 2_000L), delays, "delay doubles per consecutive failure")
        assertEquals(3, errors.size, "each failed attempt surfaces through onConnectionError")
        assertTrue(errors.all { it is IOException })
        assertEquals(4, connectCalls, "loop keeps retrying until it connects")

        source.stop()
    }

    @Test
    fun stop_cancels_the_loop_and_no_further_reconnects_occur() = runTest {
        val sessions = mutableListOf<FakeLiveSocket>()
        var connectCalls = 0

        val source = WebSocketLiveSource(
            onParseError = { _, _ -> },
            onReconnect = { },
            onConnectionError = { },
            reconnect = ReconnectPolicy(),
            scope = backgroundScope,
            connect = {
                connectCalls += 1
                FakeLiveSocket().also { sessions += it }
            },
            delayFn = { },
            random = { 0.0 },
        )

        source.start()
        source.start() // idempotent: a second start must not open a second socket.
        runCurrent()
        assertEquals(1, connectCalls, "start is idempotent")

        source.stop()
        runCurrent()

        // A drop after stop must not trigger a reconnect: the loop is cancelled.
        sessions[0].dropConnection()
        runCurrent()
        assertEquals(1, connectCalls, "no reconnect after stop")

        // Restart resumes cleanly.
        source.start()
        runCurrent()
        assertEquals(2, connectCalls, "start after stop reconnects")

        source.stop()
    }
}

/**
 * In-memory [WebSocketSession] whose inbound [incoming] channel the test feeds via [deliver] and
 * ends via [dropConnection] to model a server-side close. Only [incoming] and [send] (for the
 * best-effort close frame) are exercised by [WebSocketLiveSource]; the remaining members are inert
 * stubs the interface requires.
 */
private class FakeLiveSocket : WebSocketSession {

    private val inbound = Channel<Frame>(Channel.UNLIMITED)
    private val supervisor = SupervisorJob()

    @Suppress("InjectDispatcher")
    override val coroutineContext: CoroutineContext = Dispatchers.Unconfined + supervisor

    @Suppress("OVERRIDE_DEPRECATION")
    override var masking: Boolean = false

    @Suppress("OVERRIDE_DEPRECATION")
    override var maxFrameSize: Long = Long.MAX_VALUE

    override val extensions: List<WebSocketExtension<*>> = emptyList()

    override val incoming: ReceiveChannel<Frame> = inbound

    override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)

    /** Push a text frame the source will parse into a [LiveEvent]. */
    fun deliver(text: String) {
        inbound.trySend(Frame.Text(text))
    }

    /** End the inbound stream, mimicking a server-side or network close. */
    fun dropConnection() {
        inbound.close()
    }

    override suspend fun flush() {
        // No-op: nothing is buffered.
    }

    @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()"))
    override fun terminate() {
        supervisor.cancel()
    }

    override suspend fun send(frame: Frame) {
        // Accept (and ignore) the best-effort close frame the source sends from stop().
    }
}
