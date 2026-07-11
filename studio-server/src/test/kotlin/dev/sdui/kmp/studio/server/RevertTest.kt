package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.StudioTestSupport.bootStudio
import dev.sdui.kmp.studio.server.StudioTestSupport.ensureAccount
import dev.sdui.kmp.studio.server.StudioTestSupport.jsonClient
import dev.sdui.kmp.studio.server.StudioTestSupport.login
import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import dev.sdui.kmp.studio.server.StudioTestSupport.sampleScreenJson
import dev.sdui.kmp.studio.server.db.EditorRole
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RevertTest {

    @Test
    fun admin_can_revert_to_a_prior_version() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("admin@ex.com", "ad-pw", EditorRole.Admin, "Admin")
        val client = jsonClient()
        val token = login(client, "admin@ex.com", "ad-pw")

        // Publish version 1.
        client.put("/admin/screens/widget/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson("widget"))
        }
        val v1Resp = client.post("/admin/screens/widget/publish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, v1Resp.status)

        // Publish version 2.
        client.put("/admin/screens/widget/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson("widget"))
        }
        val v2Resp = client.post("/admin/screens/widget/publish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, v2Resp.status)
        val v2: JsonObject = v2Resp.body()
        assertEquals(2, v2["version"]?.jsonPrimitive?.content?.toIntOrNull())

        // Revert to v1 — should mint v3 with v1's body.
        val revertResp = client.post("/admin/screens/widget/revert?to=1") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, revertResp.status)
        val rev: JsonObject = revertResp.body()
        assertEquals(3, rev["version"]?.jsonPrimitive?.content?.toIntOrNull())

        // Detail now reports version 3.
        val detail: JsonObject = client.get("/admin/screens/widget") { bearerAuth(token) }.body()
        assertEquals(3, detail["version"]?.jsonPrimitive?.content?.toIntOrNull())
    }

    @Test
    fun editor_cannot_revert() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        // Admin must publish first so there's something to revert to.
        ensureAccount("ad@ex.com", "ad-pw", EditorRole.Admin, "AD")
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "ED")
        val client = jsonClient()
        val adToken = login(client, "ad@ex.com", "ad-pw")
        client.put("/admin/screens/page/draft") {
            bearerAuth(adToken)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson("page"))
        }
        client.post("/admin/screens/page/publish") { bearerAuth(adToken) }

        val edToken = login(client, "ed@ex.com", "ed-pw")
        val resp = client.post("/admin/screens/page/revert?to=1") { bearerAuth(edToken) }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }
}
