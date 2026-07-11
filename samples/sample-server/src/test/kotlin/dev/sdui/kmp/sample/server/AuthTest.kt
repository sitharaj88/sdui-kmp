package dev.sdui.kmp.sample.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthTest {

    @Test
    fun screens_are_unauthorized_without_a_token() = testApplication {
        application { sampleModule() }
        val response = client.get("/screens/home")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun login_with_wrong_password_returns_401() = testApplication {
        application { sampleModule() }
        val client = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val csrf = primeCsrf(client)
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(CSRF_HEADER, csrf)
            setBody(buildJsonObject { put("password", JsonPrimitive("nope")) })
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun login_then_authenticated_screen_request_succeeds() = testApplication {
        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }

        val token = login(httpClient, email = "user@example.com")
        val authedResponse = httpClient.get("/screens/home") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, authedResponse.status)
    }

    @Test
    fun health_endpoint_is_not_protected() = testApplication {
        application { sampleModule() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun jwks_endpoint_publishes_public_key() = testApplication {
        application { sampleModule() }
        val response = client.get("/.well-known/jwks.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val keys = body["keys"]
        assertNotNull(keys, "JWKS document must contain a keys array")
    }

    @Test
    fun login_without_csrf_token_is_forbidden() = testApplication {
        application { sampleModule() }
        val client = createClient { install(ContentNegotiation) { json(Json) } }
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("password", JsonPrimitive("password")) })
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun rate_limit_blocks_excess_logins() = testApplication {
        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val csrf = primeCsrf(httpClient)
        // 5 successes per minute, then 429s. We expect at least one 429 within 8 attempts.
        var blocked = 0
        repeat(8) {
            val response = httpClient.post("/auth/login") {
                contentType(ContentType.Application.Json)
                header(CSRF_HEADER, csrf)
                setBody(
                    buildJsonObject {
                        put("email", JsonPrimitive("rl@example.com"))
                        put("password", JsonPrimitive("password"))
                    },
                )
            }
            if (response.status == HttpStatusCode.TooManyRequests) blocked++
        }
        assertTrue(blocked >= 1, "expected at least one 429 within the burst, saw $blocked")
    }

    @Test
    fun logout_revokes_session() = testApplication {
        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val token = login(httpClient, email = "logout@example.com")
        // Sanity: protected route works.
        assertEquals(
            HttpStatusCode.OK,
            httpClient.get("/screens/home") { bearerAuth(token) }.status,
        )
        // Need a fresh CSRF for the logout POST. The cookie persists in HttpCookies; mint if absent.
        val csrf = readCsrf(httpClient) ?: primeCsrf(httpClient)
        val logoutResponse = httpClient.post("/auth/logout") {
            bearerAuth(token)
            header(CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.NoContent, logoutResponse.status)
    }

    @Test
    fun revoked_session_cannot_access_protected_route() = testApplication {
        application { sampleModule() }
        val httpClient = createClient {
            install(ContentNegotiation) { json(Json) }
            install(HttpCookies)
        }
        val token = login(httpClient, email = "revoked@example.com")
        val csrf = readCsrf(httpClient) ?: primeCsrf(httpClient)
        val logoutResponse = httpClient.post("/auth/logout") {
            bearerAuth(token)
            header(CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.NoContent, logoutResponse.status)
        // Same token now rejected.
        val response = httpClient.get("/screens/home") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private suspend fun login(client: HttpClient, email: String): String {
        val csrf = primeCsrf(client)
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(CSRF_HEADER, csrf)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive(email))
                    put("password", JsonPrimitive("password"))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, "login should succeed: $email")
        val tokenJson: JsonObject = response.body()
        val token = tokenJson["token"]?.jsonPrimitive?.content
        assertNotNull(token)
        assertTrue(token.isNotBlank())
        return token
    }

    private suspend fun primeCsrf(client: HttpClient): String {
        val response = client.get("/auth/csrf")
        assertEquals(HttpStatusCode.OK, response.status, "csrf mint should succeed")
        val cookie = readCsrf(client)
        assertNotNull(cookie, "csrf-token cookie must be set after /auth/csrf")
        return cookie
    }

    private suspend fun readCsrf(client: HttpClient): String? {
        val cookies = client.cookies("http://localhost/")
        return cookies.firstOrNull { it.name == CSRF_COOKIE }?.value
    }

    private companion object {
        const val CSRF_HEADER: String = "X-CSRF-Token"
        const val CSRF_COOKIE: String = "csrf-token"
    }
}
