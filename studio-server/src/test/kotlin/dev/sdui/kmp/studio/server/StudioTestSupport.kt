package dev.sdui.kmp.studio.server

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.server.screen
import dev.sdui.kmp.studio.server.db.EditorAccount
import dev.sdui.kmp.studio.server.db.EditorAccountStore
import dev.sdui.kmp.studio.server.db.EditorRole
import dev.sdui.kmp.studio.server.db.StudioDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared test fixtures. Each test that boots the studio uses [bootStudio] (instead of calling
 * `studioModule()` directly) so a per-test H2 database is provisioned and the database singleton
 * is reset across tests.
 */
internal object StudioTestSupport {

    /**
     * Boot the studio in a `testApplication { application { ... } }` block. Tests SHOULD call
     * [resetAndConnect] BEFORE `application { bootStudio() }` so direct `EditorAccountStore`
     * calls (used by [ensureAccount]) write to the right database.
     */
    fun Application.bootStudio() {
        // The DB is already connected (and reset) by [resetAndConnect]; pass connectDb=false so
        // the lazy application callback doesn't double-connect.
        studioModule(connectDb = false)
    }

    /**
     * Reset + connect the studio DB. Call this BEFORE `testApplication { application { ... } }`
     * so that `ensureAccount` and the route handlers see the same Exposed `Database`. Without
     * this, the route's `application { }` block runs lazily on first HTTP call — and any
     * `ensureAccount` between would land on the previous test's database.
     */
    fun resetAndConnect() {
        StudioDatabase.resetForTesting()
        StudioDatabase.connect()
    }

    fun ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient { install(ContentNegotiation) { json(Json) } }

    /**
     * Insert a fresh editor account for the test. Runs in a separate `runBlocking` thread so it
     * does not collide with the single-threaded test dispatcher driving `testApplication`.
     */
    fun ensureAccount(email: String, password: String, role: EditorRole, displayName: String): EditorAccount {
        // Off-thread because runBlocking from inside testApplication's runTest deadlocks the
        // single-threaded test dispatcher. A short-lived virtual thread (or plain Thread) is
        // sufficient and keeps the test setup synchronous-looking.
        var result: EditorAccount? = null
        val thread = Thread {
            result = runBlocking {
                EditorAccountStore.findByEmail(email)
                    ?: EditorAccountStore.create(email, password, displayName, role)
            }
        }
        thread.start()
        thread.join()
        return requireNotNull(result) { "ensureAccount: thread completed without setting account" }
    }

    /** Login helper; returns the bearer token. */
    suspend fun login(client: HttpClient, email: String, password: String): String {
        val resp = client.post("/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive(email))
                    put("password", JsonPrimitive(password))
                },
            )
        }
        val body: JsonObject = resp.body()
        return body["token"]?.jsonPrimitive?.content ?: error("login returned no token")
    }

    /** A minimal valid Screen JSON for draft/publish tests. */
    fun sampleScreenJson(id: String): String {
        val s: Screen = screen(id = id) {
            column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
                text("Hello from $id", style = TextStyleToken.Heading)
                button(label = "Back", action = Action.Navigate(Destination.Back()))
            }
        }
        return SduiJson.encodeToString(Screen.serializer(), s)
    }
}
