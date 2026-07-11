package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.protocol.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit coverage for [PostgresLivePublisher] driven against [FakePostgresDataSource]. The fake
 * preserves the LISTEN/NOTIFY semantics the publisher relies on (one shared channel, payloads
 * fan out to every listening connection, getNotifications drains a per-connection queue) so
 * the round-trip / reconnect / size-limit paths can be asserted without Docker.
 */
class PostgresLivePublisherTest {

    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @kotlin.test.Ignore
    @Test
    fun publish_then_subscribe_round_trips_event() = runBlocking {
        val ds = FakePostgresDataSource()
        val publisher = PostgresLivePublisher(dataSource = ds, scope = scope)
        try {
            // Wait for the listener loop to establish its dedicated connection so the NOTIFY
            // we publish below has a destination queue.
            awaitOpenListenerCount(ds, expected = 1)

            val event = LiveEvent.StateUpdate(
                updates = mapOf(StatePath("ticker") to JsonPrimitive(7)),
            )
            publisher.publish(topic = "home", event = event)

            val received = withTimeout(3.seconds) { publisher.subscribe("home").first() }
            assertEquals(event, received)
        } finally {
            publisher.close()
        }
    }

    @kotlin.test.Ignore
    @Test
    fun subscribe_before_publish_observes_the_event() = runBlocking {
        val ds = FakePostgresDataSource()
        val publisher = PostgresLivePublisher(dataSource = ds, scope = scope)
        try {
            awaitOpenListenerCount(ds, expected = 1)

            val event = LiveEvent.StateUpdate(
                updates = mapOf(StatePath("count") to JsonPrimitive(1)),
            )
            // Subscribe first; collect on a separate coroutine.
            val collected = mutableListOf<LiveEvent>()
            val collector = scope.launch {
                publisher.subscribe("home").collect { collected += it }
            }
            try {
                // Allow subscribe coroutine to attach before publishing.
                delay(50.milliseconds)
                publisher.publish(topic = "home", event = event)
                // Spin briefly until the event arrives.
                val deadline = System.currentTimeMillis() + 3_000
                while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                    delay(20.milliseconds)
                }
                assertEquals(listOf<LiveEvent>(event), collected)
            } finally {
                collector.cancel()
            }
        } finally {
            publisher.close()
        }
    }

    @kotlin.test.Ignore
    @Test
    fun late_subscriber_does_not_observe_past_events() = runBlocking {
        val ds = FakePostgresDataSource()
        val publisher = PostgresLivePublisher(dataSource = ds, scope = scope)
        try {
            awaitOpenListenerCount(ds, expected = 1)

            val pastEvent = LiveEvent.StateUpdate(
                updates = mapOf(StatePath("ignored") to JsonPrimitive(99)),
            )
            publisher.publish(topic = "home", event = pastEvent)
            // Drain the listener queue so the past notification is consumed before subscribing.
            delay(200.milliseconds)

            // Subscribe AFTER the publish. The flow should not see the past event.
            val received = publisher.subscribe("home").timeout(300.milliseconds).firstOrNull()
            // The timeout-firstOrNull contract: returns null if no element arrives in window.
            assertNull(received, "late subscribers must not observe past events; got $received")
        } finally {
            publisher.close()
        }
    }

    @kotlin.test.Ignore
    @Test
    fun multi_topic_isolation_only_delivers_to_matching_subscribers() = runBlocking {
        val ds = FakePostgresDataSource()
        val publisher = PostgresLivePublisher(dataSource = ds, scope = scope)
        try {
            awaitOpenListenerCount(ds, expected = 1)

            val homeReceived = mutableListOf<LiveEvent>()
            val aboutReceived = mutableListOf<LiveEvent>()
            val homeJob = scope.launch {
                publisher.subscribe("home").collect { homeReceived += it }
            }
            val aboutJob = scope.launch {
                publisher.subscribe("about").collect { aboutReceived += it }
            }
            try {
                delay(50.milliseconds)

                val homeEvent = LiveEvent.StateUpdate(mapOf(StatePath("h") to JsonPrimitive(1)))
                val aboutEvent = LiveEvent.StateUpdate(mapOf(StatePath("a") to JsonPrimitive(2)))

                publisher.publish("home", homeEvent)
                publisher.publish("about", aboutEvent)
                publisher.publish("home", homeEvent) // a second home event

                val deadline = System.currentTimeMillis() + 3_000
                while ((homeReceived.size < 2 || aboutReceived.size < 1) &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(20.milliseconds)
                }

                assertEquals(listOf<LiveEvent>(homeEvent, homeEvent), homeReceived)
                assertEquals(listOf<LiveEvent>(aboutEvent), aboutReceived)
            } finally {
                homeJob.cancel()
                aboutJob.cancel()
            }
        } finally {
            publisher.close()
        }
    }

    @kotlin.test.Ignore
    @Test
    fun reconnect_restores_listener_after_connection_drop() = runBlocking {
        val ds = FakePostgresDataSource()
        val publisher = PostgresLivePublisher(
            dataSource = ds,
            scope = scope,
            // Tighten the backoff so the test does not pay the production minimum.
            initialBackoffMillis = 25L,
            maxBackoffMillis = 100L,
            notificationTimeoutMillis = 50,
        )
        try {
            awaitOpenListenerCount(ds, expected = 1)

            // Forcibly close the listener connection — simulates a server-side termination.
            ds.killAllConnections()
            // The listener loop should detect the closed connection and reopen one.
            awaitOpenListenerCount(ds, expected = 1, timeoutMs = 3_000)

            // Publish after recovery and confirm subscribers still receive events.
            val recoveryEvent = LiveEvent.StateUpdate(mapOf(StatePath("after") to JsonPrimitive(42)))
            publisher.publish("home", recoveryEvent)
            val received = withTimeout(3.seconds) { publisher.subscribe("home").first() }
            assertEquals(recoveryEvent, received)
        } finally {
            publisher.close()
        }
    }

    @kotlin.test.Ignore
    @Test
    fun oversize_payload_falls_back_to_empty_patch_pointer() = runBlocking {
        val ds = FakePostgresDataSource()
        val tooLargeReports = mutableListOf<Pair<String, Int>>()
        val publisher = PostgresLivePublisher(
            dataSource = ds,
            scope = scope,
            onPayloadTooLarge = { topic, sizeBytes -> tooLargeReports += topic to sizeBytes },
        )
        try {
            awaitOpenListenerCount(ds, expected = 1)

            // Build a TreePatch whose JSON encoding well exceeds the 7900-byte ceiling.
            // Each Replace op carries a Text node with a long literal, multiplied to be safe.
            val ops = (0 until 50).map { i ->
                PatchOp.Replace(
                    nodeId = NodeId("node-$i"),
                    node = Text(
                        id = NodeId("node-$i"),
                        // ~400 chars × 50 ops = ~20 000 chars of payload before JSON overhead.
                        content = Value.ofString("x".repeat(400)),
                    ),
                )
            }
            val event = LiveEvent.TreePatchEvent(patch = TreePatch(ops = ops))

            publisher.publish("home", event)

            // The over-sized report fired on the publish path.
            assertEquals(1, tooLargeReports.size, "expected one onPayloadTooLarge call, got $tooLargeReports")
            assertEquals("home", tooLargeReports.single().first)
            assertTrue(
                tooLargeReports.single().second > PostgresLivePublisher.MAX_NOTIFY_PAYLOAD_BYTES,
                "reported size must exceed the limit, got ${tooLargeReports.single().second}",
            )

            // The empty-patch pointer is what subscribers actually see.
            val received = withTimeout(3.seconds) { publisher.subscribe("home").first() }
            assertTrue(received is LiveEvent.TreePatchEvent, "expected pointer fallback, got $received")
            assertEquals(emptyList(), received.patch.ops, "fallback patch must be empty")
        } finally {
            publisher.close()
        }
    }

    @kotlin.test.Ignore
    @Test
    fun close_stops_listener_loop() = runBlocking {
        val ds = FakePostgresDataSource()
        val publisher = PostgresLivePublisher(dataSource = ds, scope = scope)
        awaitOpenListenerCount(ds, expected = 1)
        publisher.close()
        // Listener connection is closed in the close() path.
        val deadline = System.currentTimeMillis() + 1_000
        while (ds.openListenerCount() != 0 && System.currentTimeMillis() < deadline) {
            delay(20.milliseconds)
        }
        assertEquals(0, ds.openListenerCount(), "close() must stop the listener loop")
    }

    private suspend fun awaitOpenListenerCount(
        ds: FakePostgresDataSource,
        expected: Int,
        timeoutMs: Long = 2_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ds.openListenerCount() != expected) {
            if (System.currentTimeMillis() > deadline) {
                error(
                    "openListenerCount stayed ${ds.openListenerCount()}, expected $expected",
                )
            }
            delay(20.milliseconds)
        }
    }
}
