package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.StudioTestSupport.bootStudio
import dev.sdui.kmp.studio.server.StudioTestSupport.ensureAccount
import dev.sdui.kmp.studio.server.StudioTestSupport.jsonClient
import dev.sdui.kmp.studio.server.StudioTestSupport.login
import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import dev.sdui.kmp.studio.server.StudioTestSupport.sampleScreenJson
import dev.sdui.kmp.studio.server.db.EditorRole
import dev.sdui.kmp.studio.server.routes.PublishNotifier
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftPublishCycleTest {

    @Test
    fun login_draft_publish_audit() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("editor@example.com", "ed-pw", EditorRole.Editor, "Editor")
        val client = jsonClient()
        val token = login(client, "editor@example.com", "ed-pw")

        // 1. PUT a draft.
        val putResp = client.put("/admin/screens/checkout/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson("checkout"))
        }
        assertEquals(HttpStatusCode.OK, putResp.status)

        // 2. GET the draft back; body must echo the canonical Screen JSON.
        val getResp = client.get("/admin/screens/checkout/draft") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val draft: JsonObject = getResp.body()
        assertEquals("checkout", draft["screenId"]?.jsonPrimitive?.content)

        // 3. Publish.
        val pubResp = client.post("/admin/screens/checkout/publish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, pubResp.status)
        val pub: JsonObject = pubResp.body()
        assertEquals(1, pub["version"]?.jsonPrimitive?.content?.toIntOrNull())

        // 4. The draft is gone after publish.
        val draftAfter = client.get("/admin/screens/checkout/draft") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, draftAfter.status)

        // 5. The screens list now includes checkout at version 1.
        val list: JsonArray = client.get("/admin/screens") { bearerAuth(token) }.body()
        val checkout = list.firstOrNull { (it as JsonObject)["id"]?.jsonPrimitive?.content == "checkout" } as JsonObject?
        assertEquals(1, checkout?.get("currentVersion")?.jsonPrimitive?.content?.toIntOrNull())

        // 6. Audit log records the drafted + published events for this screen.
        val auditResp = client.get("/admin/audit?screenId=checkout") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, auditResp.status)
        val audit: JsonArray = auditResp.body()
        val actions = audit.jsonArray.map { (it as JsonObject)["action"]?.jsonPrimitive?.content }
        assertTrue("published" in actions, "audit must record a published event; got $actions")
        assertTrue("drafted" in actions, "audit must record a drafted event; got $actions")
    }

    /**
     * Confirms the publish route invokes the injected [PublishNotifier] with the canonical
     * screen body. This is the seam [WebSocketPublishNotifier] hooks into in production —
     * the recording stub stands in here so the test stays in-process and does not need a
     * real WebSocket client.
     */
    @Test
    fun publish_invokes_notifier_with_canonical_body() = testApplication {
        resetAndConnect()
        val recorded = CopyOnWriteArrayList<Triple<String, Int, String>>()
        val recording = PublishNotifier { id, version, body ->
            recorded.add(Triple(id, version, body))
        }
        application { studioModule(notifier = recording, connectDb = false) }
        ensureAccount("editor2@example.com", "ed-pw", EditorRole.Editor, "Editor2")
        val client = jsonClient()
        val token = login(client, "editor2@example.com", "ed-pw")

        client.put("/admin/screens/profile/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson("profile"))
        }
        val pubResp = client.post("/admin/screens/profile/publish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, pubResp.status)

        // Notifier was called exactly once with the matching screen id and version 1.
        assertEquals(1, recorded.size, "expected one notifier call, got $recorded")
        val (id, version, body) = recorded.single()
        assertEquals("profile", id)
        assertEquals(1, version)
        // The body is the canonical JSON Studio stored in screen_versions; the screen id
        // round-trips through the body so a smoke check on its presence is enough.
        assertTrue("\"profile\"" in body, "expected canonical body to mention screen id; got $body")
    }
}
