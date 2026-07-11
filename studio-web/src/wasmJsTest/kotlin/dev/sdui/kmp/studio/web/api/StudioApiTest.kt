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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Boundary tests for [StudioApi]. Uses Ktor's `MockEngine` exactly the way `:transport-http`
 * tests `KtorSubmitHandler`, which keeps us off any real HTTP stack and side-steps the
 * skiko/Compose Wasm test issues that forced us to disable browser tests in `build.gradle.kts`.
 *
 * NOTE: this test source set is currently disabled in the module's `build.gradle.kts` (the
 * `wasmJsTest` task is wired to `enabled = false`) because skiko's Compose-Wasm test binary
 * link OOMs the Kotlin compiler on this repo's CI heap budget — same workaround as
 * `:transport-cache` and `:tooling-telemetry`. The tests still compile when enabled and
 * faithfully document the API shape, so they're kept here as the source of truth for the wire
 * contract until S5 revisits browser tests.
 */
class StudioApiTest {

    @Test
    fun login_success_returns_token_and_role() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = """{"token":"abc.def.ghi","expiresAt":"2026-04-30T00:00:00Z","role":"editor"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val result = api.login(email = "ed@example.test", password = "hunter2")
        val ok = assertIs<LoginResult.Success>(result)
        assertEquals("abc.def.ghi", ok.token)
        assertEquals("editor", ok.role)
        assertEquals("ed@example.test", ok.email)
        assertEquals(1, captured.size)
        assertEquals("http://studio.test/admin/auth/login", captured[0].url.toString())
        api.close()
    }

    @Test
    fun login_401_maps_to_invalid_credentials() = runTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.Unauthorized)
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val result = api.login(email = "x@y", password = "wrong")
        assertEquals(LoginResult.InvalidCredentials, result)
        api.close()
    }

    @Test
    fun list_screens_decodes_summaries_with_hasDraft() = runTest {
        val engine = MockEngine {
            respond(
                content = """[
                    {"id":"home","currentVersion":3,"updatedAt":"2026-04-25T10:00:00Z","hasDraft":true},
                    {"id":"checkout","currentVersion":null,"updatedAt":"2026-04-25T11:00:00Z","hasDraft":false}
                ]""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test/", authState = AuthState(), engine = engine)

        val screens = api.listScreens()
        assertEquals(2, screens.size)
        assertEquals("home", screens[0].id)
        assertEquals(3, screens[0].currentVersion)
        assertTrue(screens[0].hasDraft)
        assertNull(screens[1].currentVersion)
        api.close()
    }

    @Test
    fun get_screen_pretty_prints_body() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"home","version":1,"body":{"id":"home","version":1},
                    "publishedAt":"2026-04-25T10:00:00Z","editorEmail":"ed@example.test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val detail = api.getScreen("home")
        assertEquals("home", detail.id)
        assertEquals(1, detail.version)
        // Body comes back pretty-printed — at minimum it contains a newline + 2-space indent.
        assertTrue(detail.body.contains('\n'), "expected pretty-printed body, got: ${detail.body}")
        api.close()
    }

    @Test
    fun get_draft_returns_null_on_404() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"no draft for screen: home"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val draft = api.getDraft("home")
        assertNull(draft)
        api.close()
    }

    @Test
    fun put_draft_propagates_validation_violations() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"draft is not a valid Screen","details":["unknown field 'foo'"]}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val result = api.putDraft("home", """{"id":"home"}""")
        val invalid = assertIs<DraftSaveResult.Invalid>(result)
        assertEquals(listOf("unknown field 'foo'"), invalid.violations)
        api.close()
    }

    @Test
    fun put_draft_returns_saved_on_success() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"draft-1","screenId":"home","body":{"id":"home","version":2},
                    "updatedAt":"2026-04-25T12:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val result = api.putDraft("home", """{"id":"home","version":2}""")
        val saved = assertIs<DraftSaveResult.Saved>(result)
        assertEquals("home", saved.draft.screenId)
        assertEquals("draft-1", saved.draft.id)
        api.close()
    }

    @Test
    fun publish_returns_new_version_on_success() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"screenId":"home","version":4,"publishedAt":"2026-04-25T12:01:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val result = api.publish("home")
        val ok = assertIs<PublishResult.Ok>(result)
        assertEquals(4, ok.version)
        api.close()
    }

    @Test
    fun publish_no_draft_maps_to_explicit_branch() = runTest {
        val engine = MockEngine {
            respond(content = """{"error":"no draft to publish"}""", status = HttpStatusCode.NotFound)
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        assertEquals(PublishResult.NoDraft, api.publish("home"))
        api.close()
    }

    @Test
    fun revert_passes_to_query_param_and_distinguishes_forbidden() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(content = """{"error":"requires role >= admin"}""", status = HttpStatusCode.Forbidden)
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val result = api.revert("home", toVersion = 2)
        assertEquals(PublishResult.Forbidden, result)
        assertEquals(HttpMethod.Post, captured[0].method)
        assertTrue(captured[0].url.toString().contains("to=2"))
        assertTrue(captured[0].url.toString().endsWith("/admin/screens/home/revert?to=2"))
        api.close()
    }

    @Test
    fun list_versions_decodes_history() = runTest {
        val engine = MockEngine {
            respond(
                content = """[
                    {"version":3,"createdAt":"2026-04-25T10:00:00Z",
                     "publishedAt":"2026-04-25T10:01:00Z","editorId":"00000000-0000-0000-0000-000000000001"},
                    {"version":2,"createdAt":"2026-04-24T10:00:00Z",
                     "publishedAt":"2026-04-24T10:01:00Z","editorId":"00000000-0000-0000-0000-000000000001"}
                ]""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        val versions = api.listVersions("home")
        assertEquals(2, versions.size)
        assertEquals(3, versions[0].version)
        api.close()
    }

    @Test
    fun list_audit_threads_filters_through_query_params() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = """[]""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        api.listAudit(
            screenId = "home",
            editorId = "00000000-0000-0000-0000-000000000001",
            from = "2026-04-01T00:00:00Z",
            to = "2026-04-30T23:59:59Z",
            limit = 25,
        )
        val url = captured[0].url.toString()
        assertTrue(url.contains("screenId=home"), "missing screenId filter: $url")
        assertTrue(url.contains("editorId=00000000-0000-0000-0000-000000000001"), "missing editorId: $url")
        assertTrue(url.contains("from=2026-04-01"), "missing from: $url")
        assertTrue(url.contains("to=2026-04-30"), "missing to: $url")
        api.close()
    }

    @Test
    fun list_audit_blank_filters_are_omitted() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = """[]""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)

        api.listAudit(screenId = "", editorId = null, from = "", to = "  ")
        val url = captured[0].url.toString()
        assertTrue(!url.contains("screenId="), "blank screenId leaked: $url")
        assertTrue(!url.contains("editorId="), "null editorId leaked: $url")
        assertTrue(!url.contains("from="), "blank from leaked: $url")
        assertTrue(!url.contains("to="), "whitespace-only to leaked: $url")
        api.close()
    }

    @Test
    fun logout_succeeds_when_server_returns_2xx() = runTest {
        val engine = MockEngine {
            respond(content = """{"ok":true}""", status = HttpStatusCode.OK)
        }
        val api = StudioApi(baseUrl = "http://studio.test", authState = AuthState(), engine = engine)
        assertTrue(api.logout())
        api.close()
    }
}
