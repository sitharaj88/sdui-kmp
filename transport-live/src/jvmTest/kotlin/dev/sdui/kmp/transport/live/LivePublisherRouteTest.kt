package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.StatePath
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Black-box test of the route helper using `testApplication`. Verifies subscriber count
 * tracks connect/disconnect and that broadcasts surface as the next inbound frame on the
 * client side.
 */
class LivePublisherRouteTest {

    private fun Application.installLiveTestModule(publisher: WebSocketLivePublisher) {
        install(ServerWebSockets)
        routing { installLiveScreensRoute(publisher, requireAuth = false) }
    }

    @Test
    fun subscriber_count_tracks_connect_and_disconnect() = testApplication {
        val publisher = WebSocketLivePublisher()
        application { installLiveTestModule(publisher) }
        val client = createClient { install(WebSockets) }

        val session = client.webSocketSession(urlString = "/live/screens/home")

        // Publisher's register call happens on the connection coroutine; allow a brief
        // window for it to run by waiting for the count to flip.
        awaitCount(publisher, "home", expected = 1)

        // Broadcast and verify the client sees the encoded LiveEvent.
        val event = LiveEvent.StateUpdate(updates = mapOf(StatePath("x") to JsonPrimitive(42)))
        publisher.broadcast("home", event)
        val frame = withTimeout(2_000) { session.incoming.receive() }
        check(frame is Frame.Text)
        assertEquals(event, SduiJson.decodeFromString(LiveEvent.serializer(), frame.readText()))

        // Clean disconnect; the unregister in the route's finally block should fire and
        // bring the count back to zero.
        session.close()
        awaitCount(publisher, "home", expected = 0)
    }

    @Test
    fun two_clients_same_topic_both_receive() = testApplication {
        val publisher = WebSocketLivePublisher()
        application { installLiveTestModule(publisher) }
        val client = createClient { install(WebSockets) }

        val a = client.webSocketSession(urlString = "/live/screens/home")
        val b = client.webSocketSession(urlString = "/live/screens/home")
        awaitCount(publisher, "home", expected = 2)

        val event = LiveEvent.StateUpdate(updates = mapOf(StatePath("y") to JsonPrimitive(1)))
        publisher.broadcast("home", event)

        val fa = withTimeout(2_000) { a.incoming.receive() } as Frame.Text
        val fb = withTimeout(2_000) { b.incoming.receive() } as Frame.Text
        assertEquals(event, SduiJson.decodeFromString(LiveEvent.serializer(), fa.readText()))
        assertEquals(event, SduiJson.decodeFromString(LiveEvent.serializer(), fb.readText()))

        a.close()
        b.close()
        awaitCount(publisher, "home", expected = 0)
    }

    private suspend fun awaitCount(
        publisher: WebSocketLivePublisher,
        topic: String,
        expected: Int,
        timeoutMs: Long = 2_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (publisher.subscriberCount(topic) != expected) {
            if (System.currentTimeMillis() > deadline) {
                error(
                    "subscriberCount('$topic') stayed ${publisher.subscriberCount(topic)}, " +
                        "expected $expected",
                )
            }
            delay(20)
        }
    }
}
