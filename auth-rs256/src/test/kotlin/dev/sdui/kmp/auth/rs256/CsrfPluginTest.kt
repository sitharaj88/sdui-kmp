package dev.sdui.kmp.auth.rs256

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CsrfPluginTest {

    @Test
    fun rejected_post_does_not_execute_route_handler() = testApplication {
        // A CSRF-rejected mutating request must not reach the handler, so its side effects never
        // fire on a request the client sees as a 403.
        val handlerInvocations = AtomicInteger(0)
        application {
            routing {
                route("/auth") {
                    install(CsrfPlugin)
                    post("/login") {
                        handlerInvocations.incrementAndGet()
                        call.respondText("ok")
                    }
                }
            }
        }
        val response = client.post("/auth/login")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(
            0,
            handlerInvocations.get(),
            "route handler must not run for a CSRF-rejected request",
        )
    }

    @Test
    fun post_without_csrf_header_is_rejected() = testApplication {
        application {
            routing {
                route("/auth") {
                    install(CsrfPlugin)
                    post("/login") { call.respondText("ok") }
                }
            }
        }
        val response = client.post("/auth/login")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun post_with_matching_cookie_and_header_succeeds() = testApplication {
        application {
            routing {
                // Mint route — anything under /screens issues a cookie on GET.
                route("/screens") {
                    install(CsrfPlugin)
                    get("/home") { call.respondText("home") }
                }
                route("/auth") {
                    install(CsrfPlugin)
                    post("/login") { call.respondText("ok") }
                }
            }
        }
        // 1) Mint a cookie via a GET on /screens/home.
        val mint = client.get("/screens/home")
        assertEquals(HttpStatusCode.OK, mint.status)
        val cookie = mint.setCookie().firstOrNull { it.name == DEFAULT_COOKIE_NAME }
        assertNotNull(cookie, "mint route must set the csrf cookie")
        val token = cookie.value

        // 2) POST with matching cookie + header passes.
        val response = client.post("/auth/login") {
            header("Cookie", "${DEFAULT_COOKIE_NAME}=$token")
            header(DEFAULT_HEADER_NAME, token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun post_with_mismatched_header_is_rejected() = testApplication {
        application {
            routing {
                route("/auth") {
                    install(CsrfPlugin)
                    post("/login") { call.respondText("ok") }
                }
            }
        }
        val response = client.post("/auth/login") {
            header("Cookie", "${DEFAULT_COOKIE_NAME}=alice")
            header(DEFAULT_HEADER_NAME, "bob")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
