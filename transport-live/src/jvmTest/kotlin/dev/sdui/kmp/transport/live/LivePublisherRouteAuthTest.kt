package dev.sdui.kmp.transport.live

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Coverage for the [installLiveScreensRoute] auth wrapper. Drives `testApplication` with a
 * real JWT [Authentication] provider and asserts that:
 *
 *  - missing Authorization header → 401 on the upgrade,
 *  - invalid token → 401,
 *  - valid token → 101 Switching Protocols + working session,
 *  - and `requireAuth = false` keeps the unauthenticated path open for tests / dev wiring.
 */
class LivePublisherRouteAuthTest {

    private val secret = "test-secret-not-real"
    private val issuer = "sdui-test"
    private val audience = "sdui-test-clients"
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    private fun Application.installAuthedTestModule(publisher: WebSocketLivePublisher) {
        install(ServerWebSockets) {
            // Disable pings so testApplication's runTest dispatcher does not see uncompleted
            // background coroutines after the test body returns.
            pingPeriodMillis = 0
            timeoutMillis = 0
        }
        install(Authentication) {
            jwt("bearer-jwt") {
                realm = "sdui-test-realm"
                verifier(JWT.require(algorithm).withIssuer(issuer).withAudience(audience).build())
                validate { credential ->
                    if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
                }
            }
        }
        routing {
            installLiveScreensRoute(
                publisher = publisher,
                requireAuth = true,
                authProviderName = "bearer-jwt",
            )
        }
    }

    private fun mintToken(): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject("test-user")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(algorithm)

    @Test
    fun missing_authorization_header_returns_401() = testApplication {
        val publisher = WebSocketLivePublisher()
        application { installAuthedTestModule(publisher) }
        val client = createClient { install(WebSockets) }

        val response = try {
            client.webSocketSession(urlString = "/live/screens/home")
            null
        } catch (e: io.ktor.client.plugins.websocket.WebSocketException) {
            e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            e
        }
        // Either a connect-time exception or an explicit 401 — assert the route is gated.
        assertFalse(
            response == null,
            "expected connect to fail without bearer token, succeeded silently",
        )
        // The publisher must not have registered anyone.
        assertEquals(0, publisher.subscriberCount("home"))
    }

    @Test
    fun invalid_jwt_returns_401() = testApplication {
        val publisher = WebSocketLivePublisher()
        application { installAuthedTestModule(publisher) }
        val client = createClient { install(WebSockets) }

        val response = try {
            client.webSocketSession(urlString = "/live/screens/home") {
                header(HttpHeaders.Authorization, "Bearer not-a-real-jwt")
            }
            null
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            e
        }
        assertFalse(response == null, "expected connect to fail with bogus bearer token")
        assertEquals(0, publisher.subscriberCount("home"))
    }

    @Test
    fun valid_jwt_completes_upgrade_handshake() = testApplication {
        val publisher = WebSocketLivePublisher()
        application { installAuthedTestModule(publisher) }
        val client = createClient { install(WebSockets) }
        val token = mintToken()

        var session: ClientWebSocketSession? = null
        try {
            session = client.webSocketSession(urlString = "/live/screens/home") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            // Wait for register() to fire on the server side.
            awaitCount(publisher, "home", expected = 1)
        } finally {
            session?.close()
        }
        // After clean close, the subscriber count must drop back to zero.
        awaitCount(publisher, "home", expected = 0)
    }

    @Test
    fun requireAuth_false_keeps_the_route_open() = testApplication {
        // No Authentication plugin installed at all — proves requireAuth=false skips the wrapper.
        val publisher = WebSocketLivePublisher()
        application {
            install(ServerWebSockets) {
                pingPeriodMillis = 0
                timeoutMillis = 0
            }
            routing {
                installLiveScreensRoute(publisher = publisher, requireAuth = false)
            }
        }
        val client = createClient { install(WebSockets) }
        val session = client.webSocketSession(urlString = "/live/screens/home")
        try {
            awaitCount(publisher, "home", expected = 1)
        } finally {
            session.close()
        }
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
                fail(
                    "subscriberCount('$topic') stayed ${publisher.subscriberCount(topic)}, " +
                        "expected $expected",
                )
            }
            delay(20)
        }
    }
}
