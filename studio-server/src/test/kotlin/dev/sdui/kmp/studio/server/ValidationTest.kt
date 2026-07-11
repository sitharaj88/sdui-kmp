package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.StudioTestSupport.bootStudio
import dev.sdui.kmp.studio.server.StudioTestSupport.ensureAccount
import dev.sdui.kmp.studio.server.StudioTestSupport.jsonClient
import dev.sdui.kmp.studio.server.StudioTestSupport.login
import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import dev.sdui.kmp.studio.server.db.EditorRole
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationTest {

    @Test
    fun put_with_malformed_json_returns_400() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed1@ex.com", "pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed1@ex.com", "pw")
        val resp = client.put("/admin/screens/foo/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("{ this is not valid json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun put_with_structurally_invalid_screen_returns_400() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed2@ex.com", "pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed2@ex.com", "pw")
        // Structurally valid JSON but missing required Screen fields (no `id`, no `root`, no
        // version): should fail to decode as Screen.
        val resp = client.put("/admin/screens/foo/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"some_field":"some_value"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun put_with_unknown_node_kind_returns_400() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed3@ex.com", "pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed3@ex.com", "pw")
        // The discriminator `Unknown` is not a registered UiNode subtype — schema linter must reject.
        val resp = client.put("/admin/screens/foo/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "id": "foo",
                  "version": {"major":1,"minor":0},
                  "root": {
                    "kind": "Unknown",
                    "id": "foo/root"
                  }
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
