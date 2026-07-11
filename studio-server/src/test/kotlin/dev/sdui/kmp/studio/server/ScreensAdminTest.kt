package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.StudioTestSupport.bootStudio
import dev.sdui.kmp.studio.server.StudioTestSupport.ensureAccount
import dev.sdui.kmp.studio.server.StudioTestSupport.jsonClient
import dev.sdui.kmp.studio.server.StudioTestSupport.login
import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import dev.sdui.kmp.studio.server.StudioTestSupport.sampleScreenJson
import dev.sdui.kmp.studio.server.db.EditorRole
import dev.sdui.kmp.studio.server.model.ScreenListItem
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreensAdminTest {

    @Test
    fun unauthorized_without_token() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        val resp = client.get("/admin/screens")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun list_is_empty_for_a_fresh_studio() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("viewer@ex.com", "v-pw", EditorRole.Viewer, "Viewer")
        val client = jsonClient()
        val token = login(client, "viewer@ex.com", "v-pw")
        val resp = client.get("/admin/screens") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body: JsonArray = resp.body()
        assertEquals(0, body.size)
    }

    @Test
    fun get_unknown_screen_returns_404() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("v2@ex.com", "v2-pw", EditorRole.Viewer, "V2")
        val client = jsonClient()
        val token = login(client, "v2@ex.com", "v2-pw")
        val resp = client.get("/admin/screens/does-not-exist") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun malformed_draft_body_is_rejected_as_400() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed400@ex.com", "ed-pw", EditorRole.Editor, "Ed400")
        val client = jsonClient()
        val token = login(client, "ed400@ex.com", "ed-pw")
        // Syntactically broken JSON body. ContentNegotiation raises a BadRequestException which the
        // StatusPages contract maps to a clean 400 with an ErrorResponse envelope — never a 500.
        val resp = client.put("/admin/screens/broken/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("{ this is : not json ]")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun list_after_publish_includes_the_screen() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")

        // Draft + publish a screen so the listing has something to return.
        val draftResp = client.put("/admin/screens/welcome/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson("welcome"))
        }
        assertEquals(HttpStatusCode.OK, draftResp.status)
        val publishResp = client.post("/admin/screens/welcome/publish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, publishResp.status)

        // Listing now contains the welcome row.
        val listResp = client.get("/admin/screens") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, listResp.status)
        val list: JsonArray = listResp.body()
        assertTrue(list.any { (it as JsonObject)["id"]?.toString()?.contains("welcome") == true })

        // Detail returns the body with the right version.
        val detailResp = client.get("/admin/screens/welcome") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, detailResp.status)
        val detail: JsonObject = detailResp.body()
        assertEquals(1, detail["version"]?.toString()?.toIntOrNull())
    }

    /**
     * Guards the [dev.sdui.kmp.studio.server.db.ScreenStore.listScreens] projection: the listing
     * resolves each screen's `currentVersion` via the `current_version_id` join, so it must report
     * the LATEST published version per screen — even across many versions and several screens — and
     * must key the version number to the right screen (no cross-screen bleed). This exercises the
     * body-free join path that replaced the full `ScreenVersions` history scan.
     */
    @Test
    fun list_reports_latest_current_version_per_screen() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("multi@ex.com", "m-pw", EditorRole.Editor, "Multi")
        val client = jsonClient()
        val token = login(client, "multi@ex.com", "m-pw")

        suspend fun publish(screenId: String) {
            client.put("/admin/screens/$screenId/draft") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(sampleScreenJson(screenId))
            }
            val resp = client.post("/admin/screens/$screenId/publish") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

        // "alpha" ends on version 3; "beta" ends on version 1. Interleave publishes so an
        // incorrect (non-keyed) join would visibly cross the version numbers.
        publish("alpha")
        publish("beta")
        publish("alpha")
        publish("alpha")

        val listResp = client.get("/admin/screens") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, listResp.status)
        val items: List<ScreenListItem> = listResp.body()
        val byId = items.associateBy { it.id }

        assertEquals(3, byId.getValue("alpha").currentVersion)
        assertEquals(1, byId.getValue("beta").currentVersion)
    }
}
