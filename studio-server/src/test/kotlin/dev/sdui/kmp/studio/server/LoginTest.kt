package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.StudioTestSupport.bootStudio
import dev.sdui.kmp.studio.server.StudioTestSupport.ensureAccount
import dev.sdui.kmp.studio.server.StudioTestSupport.jsonClient
import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import dev.sdui.kmp.studio.server.db.EditorRole
import dev.sdui.kmp.studio.server.db.EditorSessions
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginTest {

    /**
     * Count `editor_sessions` rows off the test dispatcher thread (mirrors
     * [StudioTestSupport.ensureAccount]) so it does not deadlock the single-threaded `runTest`
     * dispatcher driving `testApplication`.
     */
    private fun sessionCount(): Long {
        var result = 0L
        val thread = Thread {
            result = runBlocking {
                newSuspendedTransaction { EditorSessions.selectAll().count() }
            }
        }
        thread.start()
        thread.join()
        return result
    }

    @Test
    fun login_is_locked_out_after_repeated_failures_and_mints_no_session() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("carol@example.com", "carol-pw", EditorRole.Editor, "Carol")
        val client = jsonClient()

        // Exhaust the default failure threshold (5) with wrong passwords — each is a 401.
        repeat(5) {
            val resp = client.post("/admin/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("email", JsonPrimitive("carol@example.com"))
                        put("password", JsonPrimitive("wrong-pw"))
                    },
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status, "failed attempt $it should be 401")
        }

        val sessionsBefore = sessionCount()

        // The next attempt — even with the CORRECT password — is locked out. Proves the throttle
        // short-circuits before bcrypt/session issue: no token, no session row.
        val locked = client.post("/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive("carol@example.com"))
                    put("password", JsonPrimitive("carol-pw"))
                },
            )
        }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        val body: JsonObject = locked.body()
        assertNull(body["token"], "a locked-out login must not mint a token")
        assertEquals(sessionsBefore, sessionCount(), "a locked-out login must not create a session row")
    }

    @Test
    fun login_with_valid_credentials_returns_token() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("alice@example.com", "alice-pw", EditorRole.Editor, "Alice")
        val client = jsonClient()
        val resp = client.post("/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive("alice@example.com"))
                    put("password", JsonPrimitive("alice-pw"))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body: JsonObject = resp.body()
        val token = body["token"]?.jsonPrimitive?.content
        assertNotNull(token, "login response must include a token")
        assertTrue(token.isNotBlank(), "token must be non-empty")
        assertEquals("editor", body["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun login_with_wrong_password_returns_401() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("bob@example.com", "bob-pw", EditorRole.Editor, "Bob")
        val client = jsonClient()
        val resp = client.post("/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive("bob@example.com"))
                    put("password", JsonPrimitive("not-bob-pw"))
                },
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun login_with_missing_email_returns_400() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        val client = jsonClient()
        val resp = client.post("/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive(""))
                    put("password", JsonPrimitive("anything"))
                },
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
