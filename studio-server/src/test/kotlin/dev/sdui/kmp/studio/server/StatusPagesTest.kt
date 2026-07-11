package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.model.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/**
 * Directly exercises [installStudioStatusPages]. Each route throws one exception shape and the
 * test asserts the mapped HTTP status plus the uniform [ErrorResponse] envelope. This proves the
 * contract in isolation — no database, no `newSuspendedTransaction` — so the constraint-violation
 * -> `409` branch (the deterministic outcome a concurrent create/publish race produces) is
 * verified without the flakiness of forcing a real SQL exception through Exposed's async
 * transaction machinery under `runTest`.
 */
class StatusPagesTest {

    private fun ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient { install(ClientContentNegotiation) { json(Json) } }

    @Suppress("ThrowsCount") // Each route deliberately throws a distinct exception shape to map.
    private fun ApplicationTestBuilder.installThrowingApp() {
        application {
            installStudioStatusPages()
            install(ContentNegotiation) { json(Json) }
            routing {
                get("/boom/ok") { call.respondText("ok") }
                get("/boom/illegal-arg") { throw IllegalArgumentException("bad input") }
                get("/boom/illegal-state") { throw IllegalStateException("no draft to publish") }
                get("/boom/bad-request") { throw BadRequestException("nope") }
                // 23505 = unique_violation in both Postgres and H2 — what a duplicate
                // create/publish under a unique index surfaces as.
                get("/boom/unique") { throw SQLException("dup key", "23505") }
                // A non-constraint SQL failure (e.g. connection loss) must stay a 500.
                get("/boom/sql-other") { throw SQLException("connection reset", "08006") }
                get("/boom/unexpected") { throw RuntimeException("kaboom: secret internal detail") }
            }
        }
    }

    @Test
    fun illegal_argument_maps_to_400() = testApplication {
        installThrowingApp()
        val resp = jsonClient().get("/boom/illegal-arg")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals("bad input", resp.body<ErrorResponse>().error)
    }

    @Test
    fun bad_request_maps_to_400() = testApplication {
        installThrowingApp()
        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/boom/bad-request").status)
    }

    @Test
    fun illegal_state_maps_to_409() = testApplication {
        installThrowingApp()
        val resp = jsonClient().get("/boom/illegal-state")
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertEquals("no draft to publish", resp.body<ErrorResponse>().error)
    }

    @Test
    fun unique_constraint_violation_maps_to_409_not_500() = testApplication {
        installThrowingApp()
        val resp = jsonClient().get("/boom/unique")
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun non_constraint_sql_error_stays_500() = testApplication {
        installThrowingApp()
        assertEquals(HttpStatusCode.InternalServerError, jsonClient().get("/boom/sql-other").status)
    }

    @Test
    fun unexpected_error_maps_to_500_without_leaking_details() = testApplication {
        installThrowingApp()
        val resp = jsonClient().get("/boom/unexpected")
        assertEquals(HttpStatusCode.InternalServerError, resp.status)
        // The stable body must NOT echo the exception message / internal detail.
        val body = resp.body<ErrorResponse>()
        assertEquals("internal error", body.error)
        assertEquals(false, body.error.contains("secret"))
    }

    @Test
    fun success_path_is_untouched() = testApplication {
        installThrowingApp()
        assertEquals(HttpStatusCode.OK, jsonClient().get("/boom/ok").status)
    }
}
