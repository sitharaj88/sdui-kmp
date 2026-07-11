package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.protocol.Value
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
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression coverage for the horizontal-scalability fix (SCALABILITY.md Phase 2A #1+#2):
 * a publish that lands on one server instance must reach a subscriber connected to a
 * **different** instance. Two "instances" are modelled as two independent
 * [WebSocketLivePublisher] fan-out registries, each with its own bridge, sharing **one**
 * [InProcessLiveBus] — the single-JVM stand-in for a shared Postgres `NOTIFY` channel. If
 * the publish path broadcast to the local registry (the pre-fix behaviour) instead of the
 * bus, instance B's subscriber would never see the event and these tests would fail.
 */
class CrossInstanceLiveBusTest {

    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun publish_on_instance_a_reaches_subscriber_on_instance_b() = runBlocking {
        val bus = InProcessLiveBus()

        // Instance A: a fan-out registry bridged to the shared bus. No local subscriber —
        // it is only the publishing side here (its own bridge exists to prove it does not
        // steal delivery from B).
        val publisherA = WebSocketLivePublisher()
        val bridgeA = bridgeTopic(bus, publisherA, "home", scope)

        // Instance B: a separate registry + bridge with a real local subscriber.
        val publisherB = WebSocketLivePublisher()
        val subscriberB = RecordingWsSession()
        publisherB.register("home", subscriberB)
        val bridgeB = bridgeTopic(bus, publisherB, "home", scope)

        try {
            // Let both bridge collectors attach to the (replay-0) shared flow before publishing.
            delay(50.milliseconds)

            // Instance A publishes via the shared bus exactly as WebSocketPublishNotifier does.
            val event = treePatchEvent()
            bus.publish("home", event)

            val frame = subscriberB.awaitSingleFrame()
            val decoded = SduiJson.decodeFromString(LiveEvent.serializer(), frame)
            assertEquals(event, decoded, "instance B must receive the event published on instance A")
        } finally {
            bridgeA.cancel()
            bridgeB.cancel()
        }
    }

    @Test
    fun dynamic_bridge_bridges_topic_on_first_subscribe_across_instances() = runBlocking {
        val bus = InProcessLiveBus()

        // Instance A: plain registry bridged to the bus, standing in as the publisher.
        val publisherA = WebSocketLivePublisher()
        val bridgeA = bridgeTopic(bus, publisherA, "home", scope)

        // Instance B: an on-demand bridge. No topic is bridged until a client subscribes —
        // registering the session is the only wiring, proving the register hook lazily
        // bridges "home" off the shared bus.
        val dynamicB = DynamicLiveBusBridge(bus, scope)
        val subscriberB = RecordingWsSession()
        dynamicB.publisher.register("home", subscriberB)

        try {
            delay(50.milliseconds)

            val event = treePatchEvent()
            bus.publish("home", event)

            val frame = subscriberB.awaitSingleFrame()
            val decoded = SduiJson.decodeFromString(LiveEvent.serializer(), frame)
            assertEquals(event, decoded, "on-demand bridge on instance B must deliver A's publish")

            // Last subscriber leaves -> the topic is evicted from B's registry.
            dynamicB.publisher.unregister("home", subscriberB)
            assertEquals(0, dynamicB.publisher.subscriberCount("home"))
        } finally {
            bridgeA.cancel()
        }
    }

    private fun treePatchEvent(): LiveEvent.TreePatchEvent {
        val root = Text(id = NodeId("root"), content = Value.ofString("hello from A"))
        return LiveEvent.TreePatchEvent(
            patch = TreePatch(ops = listOf(PatchOp.Replace(nodeId = root.id, node = root))),
        )
    }
}

/**
 * Minimal recording [WebSocketSession] capturing every outgoing [Frame.Text] payload. The
 * publisher only ever calls [send], so the channel members are inert placeholders.
 */
private class RecordingWsSession : WebSocketSession {

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
        if (frame is Frame.Text) synchronized(sentTexts) { sentTexts += frame.readText() }
    }

    /** Poll until exactly one frame has arrived (or the deadline lapses) and return it. */
    suspend fun awaitSingleFrame(timeoutMillis: Long = 2_000): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            synchronized(sentTexts) { if (sentTexts.isNotEmpty()) return sentTexts.single() }
            delay(20.milliseconds)
        }
        synchronized(sentTexts) {
            error("expected exactly one delivered frame, got $sentTexts")
        }
    }
}
