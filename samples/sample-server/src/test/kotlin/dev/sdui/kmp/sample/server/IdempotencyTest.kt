package dev.sdui.kmp.sample.server

import dev.sdui.kmp.sample.server.db.Db
import dev.sdui.kmp.sample.server.db.IDEMPOTENCY_HEADER
import dev.sdui.kmp.sample.server.db.IDEMPOTENCY_REPLAYED_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class IdempotencyTest {

    @BeforeTest
    fun setUp() {
        Db.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        Db.resetForTesting()
    }

    @Test
    fun second_login_with_same_key_replays_first_response() = testApplication {
        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val csrf = primeCsrf(httpClient)

        val first = httpClient.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, "abc-123")
            header(CSRF_HEADER, csrf)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive("user@example.com"))
                    put("password", JsonPrimitive("password"))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        // First response should not be flagged as a replay.
        assertNull(first.headers[IDEMPOTENCY_REPLAYED_HEADER])

        val second = httpClient.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, "abc-123")
            header(CSRF_HEADER, csrf)
            // Even with a different body, a replay returns the *original* response.
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive("different@example.com"))
                    put("password", JsonPrimitive("password"))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(firstBody, second.bodyAsText(), "second call must replay the cached body verbatim")
        assertEquals("true", second.headers[IDEMPOTENCY_REPLAYED_HEADER])
    }

    @Test
    fun different_idempotency_key_creates_a_new_response() = testApplication {
        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val csrf = primeCsrf(httpClient)

        val first = httpClient.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, "key-A")
            header(CSRF_HEADER, csrf)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive("a@example.com"))
                    put("password", JsonPrimitive("password"))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = httpClient.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, "key-B")
            header(CSRF_HEADER, csrf)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive("b@example.com"))
                    put("password", JsonPrimitive("password"))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, second.status)
        // Tokens are minted fresh with their own iat, so the bodies must differ.
        assertNotEquals(first.bodyAsText(), second.bodyAsText())
        // Neither response should be a replay.
        assertNull(first.headers[IDEMPOTENCY_REPLAYED_HEADER])
        assertNull(second.headers[IDEMPOTENCY_REPLAYED_HEADER])
    }

    private suspend fun primeCsrf(client: HttpClient): String {
        val response = client.get("/auth/csrf")
        assertEquals(HttpStatusCode.OK, response.status)
        val cookie = client.cookies("http://localhost/").firstOrNull { it.name == CSRF_COOKIE }
        assertNotNull(cookie, "csrf-token cookie must be set")
        return cookie.value
    }

    private companion object {
        const val CSRF_HEADER: String = "X-CSRF-Token"
        const val CSRF_COOKIE: String = "csrf-token"
    }
}
