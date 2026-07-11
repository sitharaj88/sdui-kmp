package dev.sdui.kmp.auth.rs256

import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RateLimitPluginTest {

    @Test
    fun throttled_request_does_not_execute_route_handler() = testApplication {
        // The guarded handler counts every invocation. A rate-limited request must NOT reach it,
        // otherwise its side effects (a login's session write / token mint) would fire on a
        // request the client sees rejected.
        val handlerInvocations = AtomicInteger(0)
        application {
            routing {
                route("/auth") {
                    install(RateLimitPlugin) {
                        requestsPerMinute = 1
                        keyExtractor = { "fixed-key" }
                    }
                    post("/login") {
                        handlerInvocations.incrementAndGet()
                        call.respondText("ok")
                    }
                }
            }
        }
        val first = client.post("/auth/login")
        assertEquals(HttpStatusCode.OK, first.status)
        val throttled = client.post("/auth/login")
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertEquals(
            1,
            handlerInvocations.get(),
            "route handler must run exactly once — the throttled request must not reach it",
        )
    }

    @Test
    fun burst_above_limit_returns_429_with_retry_after() = testApplication {
        application {
            routing {
                route("/auth") {
                    install(RateLimitPlugin) {
                        requestsPerMinute = 5
                        windowSeconds = 60
                        keyExtractor = { "fixed-key" } // collapse all requests into one bucket
                    }
                    post("/login") { call.respondText("ok") }
                }
            }
        }
        var ok = 0
        var blocked = 0
        repeat(10) {
            val response = client.post("/auth/login")
            when (response.status) {
                HttpStatusCode.OK -> ok++
                HttpStatusCode.TooManyRequests -> {
                    blocked++
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]
                    assertNotNull(retryAfter, "429 response must include Retry-After header")
                    assertTrue(retryAfter.toLong() in 1..60, "Retry-After must be within window: $retryAfter")
                }
                else -> error("Unexpected status: ${response.status}")
            }
        }
        assertEquals(5, ok, "first 5 requests should pass through")
        assertEquals(5, blocked, "remaining 5 requests should be blocked")
    }
}
