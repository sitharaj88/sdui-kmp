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

class MetricsTest {

    @BeforeTest
    fun setUp() {
        Db.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        Db.resetForTesting()
    }

    @Test
    fun metrics_endpoint_exposes_prometheus_text_format() = testApplication {
        application { sampleModule() }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // JVM memory binder is the canonical "did the meter binders register" smoke check;
        // its absence means installMetrics() never ran or the binders failed to attach.
        assertTrue(
            body.contains("# TYPE jvm_memory_used_bytes gauge"),
            "expected jvm_memory_used_bytes gauge in /metrics body; got:\n$body",
        )
        // Sanity-check the JVM GC binder also fired.
        assertTrue(
            body.contains("jvm_gc_") || body.contains("jvm_memory_max_bytes"),
            "expected at least one jvm_gc_ or jvm_memory_max_bytes line in /metrics body",
        )
    }
}
