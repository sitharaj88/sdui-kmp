package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import dev.sdui.kmp.studio.server.db.StudioDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the operability prelude added for PRODUCTION_READINESS #12: the `/readiness` probe
 * reflecting DB connectivity, and the `X-Request-Id` correlation header. `/health` stays a
 * trivial always-200 liveness ping (asserted here too so the two are not conflated).
 */
class ReadinessTest {

    @AfterTest
    fun tearDown() {
        // The unreachable-DB test intentionally leaves the singleton disconnected; reset so a
        // later test class starting with resetAndConnect() sees a clean slate.
        StudioDatabase.resetForTesting()
    }

    @Test
    fun readiness_is_200_and_reports_fallback_when_db_is_up() = testApplication {
        resetAndConnect()
        application { studioModule(connectDb = false) }

        val response = client.get("/readiness")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ready\":true"), "body=$body")
        // The test suite runs on the in-memory H2 fallback, so the probe reports it.
        assertTrue(body.contains("\"fallback_db\":true"), "body=$body")
    }

    @Test
    fun readiness_is_503_when_db_is_not_connected() = testApplication {
        // Boot WITHOUT connecting the DB so StudioDatabase.probe() takes the
        // "datasource not initialised" path — the same code path a Postgres outage would hit
        // (probe()'s try/catch turns a JDBC failure into an error string rather than a throw).
        StudioDatabase.resetForTesting()
        application { studioModule(connectDb = false) }

        val response = client.get("/readiness")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ready\":false"), "body=$body")
        assertTrue(body.contains("datasource not initialised"), "body=$body")
    }

    @Test
    fun health_is_always_200_regardless_of_db_state() = testApplication {
        StudioDatabase.resetForTesting()
        application { studioModule(connectDb = false) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun request_id_is_generated_when_absent_and_echoed_on_the_response() = testApplication {
        resetAndConnect()
        application { studioModule(connectDb = false) }

        val response = client.get("/health")
        val generated = response.headers[REQUEST_ID_HEADER]
        assertNotNull(generated, "server should mint an $REQUEST_ID_HEADER when the client sends none")
        assertTrue(generated.isNotBlank())
    }

    @Test
    fun inbound_request_id_is_preserved_end_to_end() = testApplication {
        resetAndConnect()
        application { studioModule(connectDb = false) }

        val correlationId = "test-correlation-1234"
        val response = client.get("/health") { header(REQUEST_ID_HEADER, correlationId) }
        assertEquals(correlationId, response.headers[REQUEST_ID_HEADER])
    }
}
