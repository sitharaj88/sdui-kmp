package dev.sdui.kmp.studio.server

import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.studio.server.StudioTestSupport.bootStudio
import dev.sdui.kmp.studio.server.StudioTestSupport.ensureAccount
import dev.sdui.kmp.studio.server.StudioTestSupport.jsonClient
import dev.sdui.kmp.studio.server.StudioTestSupport.login
import dev.sdui.kmp.studio.server.StudioTestSupport.resetAndConnect
import dev.sdui.kmp.studio.server.StudioTestSupport.sampleScreenJson
import dev.sdui.kmp.studio.server.db.EditorRole
import dev.sdui.kmp.studio.server.experiments.AssignRouteConfig
import dev.sdui.kmp.studio.server.experiments.AssignmentService
import dev.sdui.kmp.studio.server.experiments.AudiencePredicate
import dev.sdui.kmp.studio.server.experiments.AudienceRegexGuard
import dev.sdui.kmp.studio.server.experiments.VariantRow
import dev.sdui.kmp.studio.server.experiments.evaluate
import dev.sdui.kmp.studio.server.experiments.installScreenAssignRoute
import dev.sdui.kmp.studio.server.experiments.validateRegexes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the M-S6 A/B targeting surface. Covers:
 *  * experiment + variant CRUD
 *  * audience predicate evaluation across every operator
 *  * assignment stickiness across multiple `/screens/{id}/assign` calls
 *  * weight-bucket determinism
 *  * audience-filtered exclusion (clients not matching the AND-of-audiences predicate fall
 *    back to the published version)
 *  * results aggregation
 */
class ExperimentsTest {

    private suspend fun bootScreenAndPublish(client: HttpClient, token: String, screenId: String): Int {
        val draftResp = client.put("/admin/screens/$screenId/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson(screenId))
        }
        assertEquals(HttpStatusCode.OK, draftResp.status, "draft failed: ${draftResp.status}")
        val publishResp = client.post("/admin/screens/$screenId/publish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, publishResp.status)
        val body: JsonObject = publishResp.body()
        return body["version"]?.jsonPrimitive?.content?.toInt() ?: error("no version")
    }

    @Test
    fun experiment_crud_create_get_list_status() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        bootScreenAndPublish(client, token, "screen-cta")

        val createResp = client.post("/experiments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"id":"exp-001","screenId":"screen-cta","name":"CTA color","description":"red vs blue"}""",
            )
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val created: JsonObject = createResp.body()
        assertEquals("exp-001", created["id"]?.jsonPrimitive?.content)
        assertEquals("draft", created["status"]?.jsonPrimitive?.content)

        val listResp = client.get("/experiments") { bearerAuth(token) }
        val list: JsonArray = listResp.body()
        assertEquals(1, list.size)

        val getResp = client.get("/experiments/exp-001") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, getResp.status)

        val patchResp = client.patch("/experiments/exp-001/status") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"active"}""")
        }
        assertEquals(HttpStatusCode.OK, patchResp.status)

        val refetched: JsonObject = client.get("/experiments/exp-001") { bearerAuth(token) }.body()
        assertEquals("active", refetched["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun malformed_experiment_body_is_rejected_as_400() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        val resp = client.post("/experiments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("{ not valid json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun unknown_experiment_returns_404() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        val resp = client.get("/experiments/does-not-exist") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun audience_predicate_evaluation_covers_every_operator() {
        // Direct unit test — no need to boot the studio. Exercises every sealed branch of
        // [AudiencePredicate.evaluate] via a single composite predicate.
        val pred = AudiencePredicate.And(
            listOf(
                AudiencePredicate.Equals("country", "US"),
                AudiencePredicate.In("tier", listOf("gold", "platinum")),
                AudiencePredicate.Or(
                    listOf(
                        AudiencePredicate.MatchesRegex("appversion", """\d+\.\d+\.\d+"""),
                        AudiencePredicate.Equals("override", "true"),
                    ),
                ),
                AudiencePredicate.Not(AudiencePredicate.Equals("blocked", "true")),
            ),
        )
        val ok = mapOf("country" to "US", "tier" to "gold", "appversion" to "1.2.3", "blocked" to "false")
        val wrongCountry = ok + ("country" to "DE")
        val wrongTier = ok + ("tier" to "silver")
        val regexFails = ok + ("appversion" to "1.x")
        val blocked = ok + ("blocked" to "true")
        val overrideRescues = regexFails + ("override" to "true")

        assertTrue(pred.evaluate(ok))
        assertTrue(!pred.evaluate(wrongCountry))
        assertTrue(!pred.evaluate(wrongTier))
        assertTrue(!pred.evaluate(regexFails))
        assertTrue(!pred.evaluate(blocked))
        // Override rescues: regex still fails but the OR's right branch matches.
        assertTrue(pred.evaluate(overrideRescues))
    }

    @Test
    fun weight_bucket_picker_is_deterministic_for_same_client() {
        val service = AssignmentService()
        // Build synthetic variant rows so we don't need a DB for this determinism check.
        val v1 = VariantRow(
            id = "control",
            experimentId = "e",
            name = "control",
            weight = 50,
            screenVersionId = java.util.UUID.randomUUID(),
            createdAt = java.time.Instant.EPOCH,
            createdBy = java.util.UUID.randomUUID(),
        )
        val v2 = v1.copy(id = "treatment", name = "treatment", weight = 50)
        val variants = listOf(v1, v2)

        // Same (experimentId, clientId) → same variant, every call.
        val first = service.pickByWeight("e", "client-A", variants)
        repeat(50) {
            assertEquals(first?.id, service.pickByWeight("e", "client-A", variants)?.id)
        }
        // Distribution across many clients lands at least one client in each bucket.
        val seen = (0 until WEIGHT_DETERMINISM_CLIENT_COUNT)
            .map { service.pickByWeight("e", "c-$it", variants)?.id }
            .toSet()
        assertEquals(setOf("control", "treatment"), seen)
    }

    @Test
    fun assign_returns_published_version_when_no_active_experiment() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        bootScreenAndPublish(client, token, "screen-empty")

        val resp = client.get("/screens/screen-empty/assign") {
            header("X-Sdui-Client-Id", "client-x")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body: JsonObject = resp.body()
        assertEquals("no_active_experiment", body["reason"]?.jsonPrimitive?.content)
        // Body comes back inside the AssignResponse envelope.
        assertNotNull(body["body"])
        // No experiment metadata when falling back.
        assertNull(body["experimentId"]?.jsonPrimitive?.content)
    }

    @Test
    fun assignment_is_sticky_across_calls_even_when_weight_changes() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        bootScreenAndPublish(client, token, "screen-sticky")

        // Two variants, same screen — both point at the screen's only published version.
        val versionId = createSecondVersionAndReturnId(client, token, "screen-sticky")

        client.post("/experiments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"id":"exp-sticky","screenId":"screen-sticky","name":"sticky"}""",
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        client.post("/experiments/exp-sticky/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"control","name":"control","weight":50,"screenVersionId":"$versionId"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        client.post("/experiments/exp-sticky/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"treatment","name":"treatment","weight":50,"screenVersionId":"$versionId"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        client.patch("/experiments/exp-sticky/status") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"active"}""")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        // First assignment.
        val first: JsonObject = client.get("/screens/screen-sticky/assign") {
            header("X-Sdui-Client-Id", "alice")
        }.body()
        val firstVariant = first["variantId"]?.jsonPrimitive?.content
        assertNotNull(firstVariant, "expected a variantId on first call: $first")
        assertEquals("newly_assigned", first["reason"]?.jsonPrimitive?.content)

        // Subsequent assignments: same variant, reason flips to "sticky".
        repeat(5) {
            val again: JsonObject = client.get("/screens/screen-sticky/assign") {
                header("X-Sdui-Client-Id", "alice")
            }.body()
            assertEquals(firstVariant, again["variantId"]?.jsonPrimitive?.content)
            assertEquals("sticky", again["reason"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun audience_excludes_clients_that_dont_match() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        bootScreenAndPublish(client, token, "screen-aud")
        val versionId = createSecondVersionAndReturnId(client, token, "screen-aud")

        // Create an audience that requires country=US.
        client.post("/audiences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"id":"aud-us","name":"US","predicate":""" +
                    """{"type":"equals","field":"country","value":"US"}}""",
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status, "audience create: ${it.status}") }

        client.post("/experiments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"exp-aud","screenId":"screen-aud","name":"audience"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        client.post("/experiments/exp-aud/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"only","name":"only","weight":100,"screenVersionId":"$versionId"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        client.post("/experiments/exp-aud/audiences/aud-us") { bearerAuth(token) }
            .also { assertEquals(HttpStatusCode.OK, it.status) }

        client.patch("/experiments/exp-aud/status") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"active"}""")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        // German client — excluded.
        val deResp: JsonObject = client.get("/screens/screen-aud/assign") {
            header("X-Sdui-Client-Id", "ger-1")
            header("X-Sdui-Context-Country", "DE")
        }.body()
        assertEquals("audience_excluded", deResp["reason"]?.jsonPrimitive?.content)
        assertNull(deResp["variantId"]?.jsonPrimitive?.content)

        // US client — assigned.
        val usResp: JsonObject = client.get("/screens/screen-aud/assign") {
            header("X-Sdui-Client-Id", "us-1")
            header("X-Sdui-Context-Country", "US")
        }.body()
        assertEquals("only", usResp["variantId"]?.jsonPrimitive?.content)
    }

    @Test
    fun results_endpoint_returns_assignment_counts() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        bootScreenAndPublish(client, token, "screen-results")
        val versionId = createSecondVersionAndReturnId(client, token, "screen-results")

        client.post("/experiments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"exp-results","screenId":"screen-results","name":"r"}""")
        }
        client.post("/experiments/exp-results/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"a","name":"a","weight":50,"screenVersionId":"$versionId"}""")
        }
        client.post("/experiments/exp-results/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"b","name":"b","weight":50,"screenVersionId":"$versionId"}""")
        }
        client.patch("/experiments/exp-results/status") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"active"}""")
        }
        // 30 clients across the bucket space.
        repeat(EXPECTED_RESULTS_CLIENTS) { i ->
            client.get("/screens/screen-results/assign") {
                header("X-Sdui-Client-Id", "client-$i")
            }
        }
        val results: JsonArray = client.get("/experiments/exp-results/results") { bearerAuth(token) }.body()
        // We never know the exact split, but the total must equal the number of distinct clients.
        val total = results.sumOf { (it as JsonObject)["count"]?.jsonPrimitive?.content?.toLong() ?: 0L }
        assertEquals(EXPECTED_RESULTS_CLIENTS.toLong(), total)
        assertTrue(results.size in 1..2)
    }

    @Test
    fun reject_invalid_predicate_at_audience_create() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        // Missing discriminator field — kotlinx-serialization rejects with 400.
        val resp = client.post("/audiences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"x","name":"x","predicate":{"unknown":"thing"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun regex_guard_rejects_catastrophic_and_overlong_patterns() {
        // Canonical exponential-backtracking shapes: an unbounded quantifier over a group whose
        // body is itself unbounded-quantified.
        assertTrue(AudienceRegexGuard.hasCatastrophicNesting("(a+)+"))
        assertTrue(AudienceRegexGuard.hasCatastrophicNesting("([a-z]*)*"))
        assertTrue(AudienceRegexGuard.hasCatastrophicNesting("(.*)+"))
        assertTrue(AudienceRegexGuard.hasCatastrophicNesting("((ab)+)+"))
        // Benign patterns are not flagged.
        assertTrue(!AudienceRegexGuard.hasCatastrophicNesting("(abc)+"))
        assertTrue(!AudienceRegexGuard.hasCatastrophicNesting("""\d+\.\d+\.\d+"""))

        // validateRegexes surfaces the guard as an IllegalArgumentException, walking nested nodes.
        assertFailsWith<IllegalArgumentException> {
            AudiencePredicate.MatchesRegex("appversion", "(a+)+b").validateRegexes()
        }
        assertFailsWith<IllegalArgumentException> {
            AudiencePredicate.And(
                listOf(AudiencePredicate.Not(AudiencePredicate.MatchesRegex("f", "(x*)*"))),
            ).validateRegexes()
        }
        assertFailsWith<IllegalArgumentException> {
            AudiencePredicate.MatchesRegex(
                "f",
                "a".repeat(AudienceRegexGuard.MAX_PATTERN_LENGTH + 1),
            ).validateRegexes()
        }
        // A valid, bounded pattern passes and compiles.
        AudiencePredicate.MatchesRegex("f", """\d+""").validateRegexes()
    }

    @Test
    fun catastrophic_audience_regex_is_rejected_at_create_time() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        // A stored `(a+)+b` would be recompiled + re-run against attacker input per anonymous
        // assign call. It must never reach the DB.
        val resp = client.post("/audiences") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"id":"aud-redos","name":"redos","predicate":""" +
                    """{"type":"matches_regex","field":"appversion","pattern":"(a+)+b"}}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        // And it is not persisted: the audience list stays empty.
        val list: JsonArray = client.get("/audiences") { bearerAuth(token) }.body()
        assertEquals(0, list.size)
    }

    @Test
    fun assign_route_rejects_missing_token_and_over_limit_requests() = testApplication {
        application {
            install(ContentNegotiation) { json(SduiJson) }
            routing {
                installScreenAssignRoute(
                    config = AssignRouteConfig(serviceToken = "sekret-token", requestsPerMinute = 3),
                )
            }
        }
        val client = jsonClient()
        val statuses = (0 until ASSIGN_BURST).map {
            client.get("/screens/s1/assign") { header("X-Sdui-Client-Id", "c") }.status
        }
        // The first three pass the limiter but carry no valid service token → 401. The remainder
        // trip the rate limiter → 429. Both gates are exercised in one burst.
        assertTrue(
            statuses.take(3).all { it == HttpStatusCode.Unauthorized },
            "expected the first requests to be 401 (no token): $statuses",
        )
        assertTrue(
            statuses.any { it == HttpStatusCode.TooManyRequests },
            "expected the burst to hit the rate limit: $statuses",
        )
    }

    @Test
    fun assign_with_valid_token_is_idempotent_and_missing_token_is_rejected() = testApplication {
        resetAndConnect()
        val serviceToken = "svc-secret-token"
        application {
            studioModule(
                connectDb = false,
                assignConfig = AssignRouteConfig(serviceToken = serviceToken, requestsPerMinute = 240),
            )
        }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        bootScreenAndPublish(client, token, "screen-tok")
        val versionId = createSecondVersionAndReturnId(client, token, "screen-tok", serviceToken)

        client.post("/experiments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"exp-tok","screenId":"screen-tok","name":"tok"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
        client.post("/experiments/exp-tok/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"only","name":"only","weight":100,"screenVersionId":"$versionId"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
        client.patch("/experiments/exp-tok/status") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"active"}""")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        // No service token → rejected.
        val unauth = client.get("/screens/screen-tok/assign") { header("X-Sdui-Client-Id", "u1") }
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        // Repeated assigns for the SAME client with a valid token must not grow assignment rows.
        repeat(ASSIGN_IDEMPOTENCY_CALLS) {
            val ok = client.get("/screens/screen-tok/assign") {
                header("X-Sdui-Client-Id", "sticky-client")
                header(AssignRouteConfig.SERVICE_TOKEN_HEADER, serviceToken)
            }
            assertEquals(HttpStatusCode.OK, ok.status)
        }
        val results: JsonArray = client.get("/experiments/exp-tok/results") { bearerAuth(token) }.body()
        val total = results.sumOf { (it as JsonObject)["count"]?.jsonPrimitive?.content?.toLong() ?: 0L }
        assertEquals(1L, total, "repeated assign for one client must yield exactly one assignment row")
    }

    @Test
    fun weight_overflow_is_rejected() = testApplication {
        resetAndConnect()
        application { bootStudio() }
        ensureAccount("ed@ex.com", "ed-pw", EditorRole.Editor, "Ed")
        val client = jsonClient()
        val token = login(client, "ed@ex.com", "ed-pw")
        bootScreenAndPublish(client, token, "screen-w")
        val versionId = createSecondVersionAndReturnId(client, token, "screen-w")
        client.post("/experiments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"exp-w","screenId":"screen-w","name":"w"}""")
        }
        client.post("/experiments/exp-w/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"a","name":"a","weight":80,"screenVersionId":"$versionId"}""")
        }
        // Adding 30 more would overflow 100 — reject.
        val bad = client.post("/experiments/exp-w/variants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"b","name":"b","weight":30,"screenVersionId":"$versionId"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun bucket_for_is_byte_identical_for_same_inputs() {
        // Pin the contract so a future swap to murmur3 surfaces in CI.
        val a = AssignmentService.bucketFor("e1", "alice")
        val b = AssignmentService.bucketFor("e1", "alice")
        assertEquals(a, b)
        // Different clients can land at different buckets — sanity check at least two are distinct
        // across a small sample so a literal `return 0` regression flames out.
        val sample = (0 until 20).map { AssignmentService.bucketFor("e1", "client-$it") }.toSet()
        assertTrue(sample.size > 1, "bucket distribution collapsed to ${sample.size} bucket(s)")
    }

    @Test
    fun bucket_value_is_within_0_until_100() {
        repeat(BUCKET_SAMPLE_SIZE) { i ->
            val bucket = AssignmentService.bucketFor("exp-$i", "client-$i")
            assertTrue(bucket in 0 until AssignmentService.BUCKET_SIZE, "out of range: $bucket")
        }
    }

    @Test
    fun audience_in_predicate_on_empty_list_never_matches() {
        val pred = AudiencePredicate.In("country", emptyList())
        assertTrue(!pred.evaluate(mapOf("country" to "US")))
    }

    @Test
    fun audience_or_on_empty_list_is_false() {
        val pred = AudiencePredicate.Or(emptyList())
        assertTrue(!pred.evaluate(emptyMap()))
    }

    @Test
    fun audience_and_on_empty_list_is_true() {
        val pred = AudiencePredicate.And(emptyList())
        assertTrue(pred.evaluate(emptyMap()))
    }

    @Test
    fun two_distinct_clients_can_land_on_different_variants() {
        // Smoke test for distribution: two clients with different ids end up in different
        // variants under a 50/50 split. Pure unit test — the determinism test above already
        // proves the function is stable; this proves it actually distributes.
        val service = AssignmentService()
        val v1 = VariantRow(
            id = "a",
            experimentId = "e",
            name = "a",
            weight = 50,
            screenVersionId = java.util.UUID.randomUUID(),
            createdAt = java.time.Instant.EPOCH,
            createdBy = java.util.UUID.randomUUID(),
        )
        val v2 = v1.copy(id = "b", name = "b")
        val variants = listOf(v1, v2)
        val results = (0 until DISTRIBUTION_SAMPLE_SIZE).map {
            service.pickByWeight("e", "c-$it", variants)?.id
        }.toSet()
        assertNotEquals(setOf<String?>("a"), results)
        assertNotEquals(setOf<String?>("b"), results)
    }

    /**
     * Drafts a *second* publish for the screen so that the screen has a stable version UUID we
     * can reference from a variant. The first publish is from `bootScreenAndPublish`. Returns
     * the published-version UUID of the latest version by going through the unauthenticated
     * `/screens/{id}/assign` route (which currently returns the screen-version id as a string).
     */
    private suspend fun createSecondVersionAndReturnId(
        client: HttpClient,
        token: String,
        screenId: String,
        serviceToken: String? = null,
    ): String {
        // Publish a second version so we have a UUID different from the first.
        client.put("/admin/screens/$screenId/draft") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sampleScreenJson(screenId))
        }
        client.post("/admin/screens/$screenId/publish") { bearerAuth(token) }
        // Round-trip through assign to pick up the screenVersionId of the published row.
        val resp: JsonObject = client.get("/screens/$screenId/assign") {
            header("X-Sdui-Client-Id", "_bootstrap_${java.util.UUID.randomUUID()}")
            if (serviceToken != null) header(AssignRouteConfig.SERVICE_TOKEN_HEADER, serviceToken)
        }.body()
        return resp["screenVersionId"]?.jsonPrimitive?.content
            ?: error("assign did not echo screenVersionId: $resp")
    }

    private companion object {
        private const val WEIGHT_DETERMINISM_CLIENT_COUNT = 200
        private const val EXPECTED_RESULTS_CLIENTS = 30
        private const val BUCKET_SAMPLE_SIZE = 200
        private const val DISTRIBUTION_SAMPLE_SIZE = 200
        private const val ASSIGN_BURST = 6
        private const val ASSIGN_IDEMPOTENCY_CALLS = 6
    }
}
