package dev.sdui.kmp.sample.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M-S6: covers the sample-server's optional integration with `:studio-server`'s assignment
 * service. Two tests:
 *  * [fallback_when_studio_unset] — STUDIO_BASE_URL not configured (the default for the existing
 *    E2E test suite) → server keeps serving its locally-defined screens. Documents the
 *    no-regression contract.
 *  * [happy_path_with_mocked_studio] — when a `StudioAssignmentClient` IS configured, the
 *    server forwards (clientId, context) to it and serves the studio's response body verbatim.
 */
class StudioAssignmentTest {

    @AfterTest
    fun cleanup() {
        resetStudioAssignmentClientForTesting()
    }

    @Test
    fun fallback_when_studio_unset() = testApplication {
        // Default state: no studio configured. The existing /screens/about returns the locally
        // defined screen. This is the same code path every other ScreensTest exercises today.
        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val token = login(httpClient, "demo@example.com")

        val resp = httpClient.get("/screens/about") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        // The locally-defined aboutScreen() emits a Screen JSON with "id":"about".
        assertTrue(body.contains("\"id\":\"about\""), "expected about screen body, got: $body")
    }

    @Test
    fun happy_path_with_mocked_studio() = testApplication {
        // Inject a MockEngine-backed client BEFORE the application boots so the first /screens
        // request hits the mock instead of trying CIO + a real socket.
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            // Echo a synthetic screen body so the test can prove the studio's response is what
            // the sample-server forwards.
            respond(
                content = """{
                    "screenId":"about",
                    "experimentId":"exp-1",
                    "variantId":"variant-a",
                    "screenVersionId":"00000000-0000-0000-0000-000000000001",
                    "reason":"newly_assigned",
                    "body":{"hello":"from-studio"}
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        setStudioAssignmentClientForTesting(StudioAssignmentClient("http://studio.test", engine))

        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val token = login(httpClient, "alice@example.com")

        val resp = httpClient.get("/screens/about") {
            bearerAuth(token)
            header("X-Sdui-Client-Id", "alice")
            header("X-Sdui-Context-Country", "US")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        // The sample-server forwarded the studio's `body` JsonElement verbatim.
        assertTrue(body.contains("\"hello\":\"from-studio\""), "expected studio body, got: $body")

        // The mock saw the right URL, headers, and forwarded context.
        assertEquals(1, captured.size)
        val req = captured.single()
        assertTrue(req.url.toString().endsWith("/screens/about/assign"), "url: ${req.url}")
        assertEquals("alice", req.headers["X-Sdui-Client-Id"])
        assertEquals("US", req.headers["X-Sdui-Context-Country"])
    }

    private suspend fun login(client: HttpClient, email: String): String {
        val csrfResp = client.get("/auth/csrf")
        assertEquals(HttpStatusCode.OK, csrfResp.status)
        val csrf = client.cookies("http://localhost/").firstOrNull { it.name == "csrf-token" }?.value
            ?: error("csrf cookie missing")
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive(email))
                    put("password", JsonPrimitive("password"))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, "login failed: ${resp.status} ${resp.bodyAsText()}")
        val token = resp.body<JsonObject>()["token"]?.jsonPrimitive?.content
        assertNotNull(token)
        return token
    }
}
