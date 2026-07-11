package dev.sdui.kmp.studio.web.api

import dev.sdui.kmp.studio.web.state.AuthState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * M-S6 boundary tests for the experiments + audiences slice of [StudioApi]. Same MockEngine
 * pattern as [StudioApiTest]. Note: the wasmJs test task is currently disabled in
 * `build.gradle.kts` to dodge the skiko/Compose-Wasm test-link OOM, so these tests document
 * the wire contract rather than run on every CI build — same posture as the surrounding tests.
 */
class ExperimentsApiTest {

    @Test
    fun list_experiments_threads_filters_through_query_params() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = """[
                    {"id":"e1","screenId":"home","name":"hero color","status":"active",
                     "createdAt":"2026-04-25T10:00:00Z","updatedAt":"2026-04-25T10:00:00Z",
                     "createdBy":"00000000-0000-0000-0000-000000000001"}
                ]""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)
        val list = api.listExperiments(screenId = "home", status = "active")
        assertEquals(1, list.size)
        assertEquals("e1", list[0].id)
        val url = captured[0].url.toString()
        assertTrue(url.contains("screenId=home"), "missing screenId filter: $url")
        assertTrue(url.contains("status=active"), "missing status filter: $url")
        api.close()
    }

    @Test
    fun set_experiment_status_uses_patch_with_json_body() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(content = """{"status":"paused"}""", status = HttpStatusCode.OK)
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)
        val ok = api.setExperimentStatus("e1", "paused")
        assertTrue(ok)
        val req = captured.single()
        assertEquals(HttpMethod.Patch, req.method)
        assertTrue(req.url.toString().endsWith("/experiments/e1/status"))
        api.close()
    }

    @Test
    fun list_audiences_decodes_predicate_as_jsonelement() = runTest {
        val engine = MockEngine {
            respond(
                content = """[
                    {"id":"aud-us","name":"US users","predicate":{"type":"equals","field":"country","value":"US"},
                     "createdAt":"2026-04-25T10:00:00Z"}
                ]""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)
        val list = api.listAudiences()
        assertEquals(1, list.size)
        assertEquals("aud-us", list[0].id)
        // Predicate forwarded as a JsonElement; the editor pane re-renders it without trying to
        // re-decode into the sealed hierarchy (which lives server-side only).
        assertTrue(list[0].predicate.toString().contains("country"))
        api.close()
    }

    @Test
    fun experiment_results_decodes_counts() = runTest {
        val engine = MockEngine {
            respond(
                content = """[{"variantId":"a","count":12},{"variantId":"b","count":8}]""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)
        val results = api.experimentResults("e1")
        assertEquals(2, results.size)
        assertEquals(12L, results[0].count)
        api.close()
    }

    @Test
    fun promote_variant_returns_true_on_success() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(content = """{"ok":true}""", status = HttpStatusCode.OK)
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)
        assertTrue(api.promoteVariant("e1", "v1"))
        val req = captured.single()
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(req.url.toString().endsWith("/experiments/e1/variants/v1/promote"))
        api.close()
    }

    @Test
    fun link_audience_targets_correct_endpoint() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(content = """{"ok":true}""", status = HttpStatusCode.OK)
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)
        assertTrue(api.linkAudience("e1", "aud-us"))
        assertTrue(captured.single().url.toString().endsWith("/experiments/e1/audiences/aud-us"))
        api.close()
    }
}
