package dev.sdui.kmp.sample.server

import dev.sdui.kmp.sample.server.db.Db
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadinessTest {

    @BeforeTest
    fun setUp() {
        Db.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        Db.resetForTesting()
    }

    @Test
    fun readiness_is_200_when_db_is_up() = testApplication {
        application { sampleModule() }
        val response = client.get("/readiness")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ready\":true"), "body=$body")
    }

    @Test
    fun readiness_is_503_when_db_is_unreachable() = testApplication {
        // Boot the module without connecting the DB so `Db.probe()` returns the
        // "datasource not initialised" error path. This is the same code path that
        // would fire on a Postgres outage in production (where probe()'s try/catch
        // catches the JDBC connection failure and returns an error string).
        application { sampleModuleNoDb() }
        val response = client.get("/readiness")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ready\":false"), "body=$body")
        assertTrue(body.contains("datasource not initialised"), "body=$body")
    }
}
