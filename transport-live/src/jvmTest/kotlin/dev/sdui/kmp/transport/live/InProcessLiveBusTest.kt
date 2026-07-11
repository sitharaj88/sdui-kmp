package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.StatePath
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coverage of the in-process [LiveBus] plus the [bridgeTopic] adapter that wires a bus into
 * the [WebSocketLivePublisher]'s WebSocket fan-out. Together they are the dev-mode (and
 * single-process) equivalent of the Postgres backend, so the same round-trip / topic-isolation
 * shape is tested here.
 */
class InProcessLiveBusTest {

    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun publish_then_subscribe_round_trips() = runBlocking {
        val bus = InProcessLiveBus()
        val collected = mutableListOf<LiveEvent>()
        val collector = scope.launch {
            bus.subscribe("home").collect { collected += it }
        }
        try {
            delay(20.milliseconds)
            val event = LiveEvent.StateUpdate(mapOf(StatePath("k") to JsonPrimitive(1)))
            bus.publish("home", event)
            val deadline = System.currentTimeMillis() + 1_000
            while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(20.milliseconds)
            }
            assertEquals(listOf<LiveEvent>(event), collected)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun bridge_forwards_bus_events_to_websocket_publisher() = runBlocking {
        val bus = InProcessLiveBus()
        val publisher = WebSocketLivePublisher()
        val recording = BusBridgeRecordingSession()
        publisher.register("home", recording)

        val bridge = bridgeTopic(bus, publisher, "home", scope)
        try {
            delay(20.milliseconds)
            val event = LiveEvent.StateUpdate(mapOf(StatePath("x") to JsonPrimitive(7)))
            bus.publish("home", event)
            // Wait for the bridge to fan it out to the recording session.
            val deadline = System.currentTimeMillis() + 1_000
            while (recording.frames().isEmpty() && System.currentTimeMillis() < deadline) {
                delay(20.milliseconds)
            }
            val frames = recording.frames()
            assertEquals(1, frames.size, "expected exactly one fan-out frame, got $frames")
            assertTrue(frames.single().contains("\"state_update\""), "frame: ${frames.single()}")
        } finally {
            bridge.cancel()
        }
    }
}

private class BusBridgeRecordingSession : WebSocketSession {

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
    override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)

    override suspend fun flush() = Unit

    @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()"))
    override fun terminate() {
        supervisor.cancel()
    }

    override suspend fun send(frame: Frame) {
        if (frame is Frame.Text) sentTexts += frame.readText()
    }

    fun frames(): List<String> = sentTexts.toList()
}
