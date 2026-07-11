package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.StudioTestSupport.bootStudio
import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {

    @BeforeTest
    fun setUp() {
        resetAndConnect()
    }

    @Test
    fun metrics_endpoint_exposes_prometheus_text_format() = testApplication {
        application { bootStudio() }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // The JVM memory binder is the canonical smoke check — present iff installMetrics()
        // ran AND the JvmMemoryMetrics binder attached.
        assertTrue(
            body.contains("# TYPE jvm_memory_used_bytes gauge"),
            "expected jvm_memory_used_bytes gauge in /metrics body; got:\n$body",
        )
    }
}
