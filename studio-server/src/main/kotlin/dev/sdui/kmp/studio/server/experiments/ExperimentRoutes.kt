package dev.sdui.kmp.studio.server.experiments

import dev.sdui.kmp.auth.rs256.RateLimitPlugin
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.studio.server.db.ScreenStore
import dev.sdui.kmp.studio.server.model.ErrorResponse
import dev.sdui.kmp.studio.server.rbac.Permission
import dev.sdui.kmp.studio.server.routes.livePrincipal
import dev.sdui.kmp.studio.server.routes.requirePermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import java.security.MessageDigest
import java.util.UUID

/**
 * Admin REST routes for the A/B experiments + audiences surface. Every route here lives inside
 * the JWT [authenticate] block — same posture as `installScreensAdminRoutes`. The
 * `/screens/{id}/assign` route is mounted separately in [installScreenAssignRoute] because it is
 * consumed by upstream sample servers rather than editor UIs; it is guarded by a service token
 * plus a rate limiter (see [AssignRouteConfig]) instead of the editor JWT.
 *
 * Routes:
 *  - `POST   /experiments`                              create (Editor)
 *  - `GET    /experiments?screenId=&status=`            list (Viewer)
 *  - `GET    /experiments/{id}`                          fetch one (Viewer)
 *  - `PATCH  /experiments/{id}/status`                  status transition (Editor)
 *  - `POST   /experiments/{id}/variants`                add variant (Editor)
 *  - `GET    /experiments/{id}/variants`                list variants (Viewer)
 *  - `POST   /experiments/{id}/variants/{vid}/promote`   admin-promote variant (Admin)
 *  - `POST   /audiences`                                 create audience (Editor)
 *  - `GET    /audiences`                                 list audiences (Viewer)
 *  - `POST   /experiments/{id}/audiences/{audienceId}`   link audience (Editor)
 *  - `GET    /experiments/{id}/results`                  assignment counts (Viewer)
 */
@Suppress("LongMethod", "ComplexMethod")
public fun Route.installExperimentRoutes(
    jwtAuthName: String,
    service: AssignmentService = AssignmentService(),
    store: ExperimentStore = ExperimentStore,
    assignConfig: AssignRouteConfig = AssignRouteConfig.fromEnv(),
) {
    authenticate(jwtAuthName) {
        post("/experiments") {
            call.requirePermission(Permission.EXPERIMENTS_CREATE) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val req = call.receiveOrBadRequest<CreateExperimentRequest>() ?: return@requirePermission
                if (!validateExperimentId(req.id)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid experiment id"))
                    return@requirePermission
                }
                val row = try {
                    store.createExperiment(req.id, req.screenId, req.name, req.description, principal.editorId)
                } catch (t: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(t.message ?: "invalid request"))
                    return@requirePermission
                } catch (t: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                    // Detekt SwallowedException: collapsed into a 409 here — the route layer's
                    // contract is HTTP semantics only. The original is logged upstream.
                    call.application.environment.log.debug("conflict on experiment create: {}", t.message)
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("experiment id already exists"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.Created, row.toDto())
            }
        }

        get("/experiments") {
            call.requirePermission(Permission.EXPERIMENTS_READ) {
                val screenId = call.request.queryParameters["screenId"]?.takeIf { it.isNotBlank() }
                val status = call.request.queryParameters["status"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ExperimentStatus.parse(it) }
                val rows = store.listExperiments(screenId, status).map { it.toDto() }
                call.respond(HttpStatusCode.OK, rows)
            }
        }

        get("/experiments/{id}") {
            call.requirePermission(Permission.EXPERIMENTS_READ) {
                val id = call.parameters["id"].orEmpty()
                val row = store.getExperiment(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("experiment not found: $id"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.OK, row.toDto())
            }
        }

        patch("/experiments/{id}/status") {
            call.requirePermission(Permission.EXPERIMENTS_UPDATE) {
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveOrBadRequest<UpdateStatusRequest>() ?: return@requirePermission
                val target = ExperimentStatus.values().firstOrNull { it.wire == req.status }
                if (target == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid status: ${req.status}"))
                    return@requirePermission
                }
                val updated = store.updateStatus(id, target)
                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("experiment not found: $id"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to target.wire))
            }
        }

        post("/experiments/{id}/variants") {
            call.requirePermission(Permission.EXPERIMENTS_CREATE) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val experimentId = call.parameters["id"].orEmpty()
                val req = call.receiveOrBadRequest<CreateVariantRequest>() ?: return@requirePermission
                val versionUuid = runCatching { UUID.fromString(req.screenVersionId) }.getOrNull()
                if (versionUuid == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid screenVersionId UUID"))
                    return@requirePermission
                }
                if (req.weight < 0 || req.weight > MAX_WEIGHT) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("weight must be 0..100"))
                    return@requirePermission
                }
                val existing = store.listVariants(experimentId)
                val newTotal = existing.sumOf { it.weight } + req.weight
                if (newTotal > MAX_WEIGHT) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("variant weights would sum to $newTotal (must be <= $MAX_WEIGHT)"),
                    )
                    return@requirePermission
                }
                val row = try {
                    store.addVariant(req.id, experimentId, req.name, req.weight, versionUuid, principal.editorId)
                } catch (t: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(t.message ?: "invalid variant"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.Created, row.toDto())
            }
        }

        get("/experiments/{id}/variants") {
            call.requirePermission(Permission.EXPERIMENTS_READ) {
                val experimentId = call.parameters["id"].orEmpty()
                val rows = store.listVariants(experimentId).map { it.toDto() }
                call.respond(HttpStatusCode.OK, rows)
            }
        }

        post("/experiments/{id}/variants/{vid}/promote") {
            call.requirePermission(Permission.EXPERIMENTS_PUBLISH) {
                val experimentId = call.parameters["id"].orEmpty()
                val variantId = call.parameters["vid"].orEmpty()
                val variant = store.getVariant(variantId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("variant not found: $variantId"))
                    return@requirePermission
                }
                if (variant.experimentId != experimentId) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("variant does not belong to experiment"))
                    return@requirePermission
                }
                val experiment = store.getExperiment(experimentId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("experiment not found"))
                    return@requirePermission
                }
                val ok = ScreenStore.setCurrentVersion(experiment.screenId, variant.screenVersionId)
                if (!ok) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("screen or version not found"))
                    return@requirePermission
                }
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "screenId" to experiment.screenId,
                        "promotedVariantId" to variantId,
                        "newPublishedVersionId" to variant.screenVersionId.toString(),
                    ),
                )
            }
        }

        post("/audiences") {
            call.requirePermission(Permission.AUDIENCES_CREATE) {
                val principal = call.livePrincipal() ?: return@requirePermission
                val req = call.receiveOrBadRequest<CreateAudienceRequest>() ?: return@requirePermission
                // Validate + COMPILE every regex now, before storing. A pattern that is
                // over-length, catastrophically nested, or invalid is rejected here so it can
                // never become a stored, per-request-recompiled DoS primitive on the assign path.
                try {
                    req.predicate.validateRegexes()
                } catch (t: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid predicate: ${t.message}"))
                    return@requirePermission
                }
                val row = try {
                    store.createAudience(req.id, req.name, req.description, req.predicate, principal.editorId)
                } catch (t: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                    call.application.environment.log.debug("conflict on audience create: {}", t.message)
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("audience id already exists"))
                    return@requirePermission
                }
                call.respond(HttpStatusCode.Created, row.toDto())
            }
        }

        get("/audiences") {
            call.requirePermission(Permission.AUDIENCES_READ) {
                call.respond(HttpStatusCode.OK, store.listAudiences().map { it.toDto() })
            }
        }

        post("/experiments/{id}/audiences/{audienceId}") {
            call.requirePermission(Permission.EXPERIMENTS_UPDATE) {
                val experimentId = call.parameters["id"].orEmpty()
                val audienceId = call.parameters["audienceId"].orEmpty()
                if (store.getExperiment(experimentId) == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("experiment not found"))
                    return@requirePermission
                }
                if (store.getAudience(audienceId) == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("audience not found"))
                    return@requirePermission
                }
                store.linkAudience(experimentId, audienceId)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("experimentId" to experimentId, "audienceId" to audienceId),
                )
            }
        }

        get("/experiments/{id}/results") {
            call.requirePermission(Permission.EXPERIMENTS_READ) {
                val experimentId = call.parameters["id"].orEmpty()
                val rows = store.assignmentCounts(experimentId).map { VariantCountDto(it.variantId, it.count) }
                call.respond(HttpStatusCode.OK, rows)
            }
        }
    }

    installScreenAssignRoute(service, assignConfig)
}

/**
 * Configuration for the service-to-service `/screens/{id}/assign` route.
 *
 * The assign route is consumed by upstream servers (the sample-server's `StudioAssignmentClient`),
 * not by browsers holding a studio JWT — so it is guarded by a shared service token plus a
 * per-caller rate limiter rather than the editor JWT provider.
 *
 * @property serviceToken required value of the `X-Sdui-Service-Token` header. When `null` the
 *   route is open (dev-only): [fromEnv] only yields a `null` token inside an explicit dev
 *   environment and otherwise refuses to start.
 * @property requestsPerMinute per-remote-host request budget enforced by the rate limiter.
 */
public class AssignRouteConfig(
    public val serviceToken: String?,
    public val requestsPerMinute: Int = DEFAULT_ASSIGN_RATE_LIMIT,
) {
    public companion object {
        /** Default per-minute budget for a single caller on the assign route. */
        public const val DEFAULT_ASSIGN_RATE_LIMIT: Int = 240

        /** Header carrying the shared service token. */
        public const val SERVICE_TOKEN_HEADER: String = "X-Sdui-Service-Token"

        /** Environment variable holding the required service token. */
        public const val TOKEN_ENV: String = "STUDIO_ASSIGN_SERVICE_TOKEN"

        /** Environment variable overriding [DEFAULT_ASSIGN_RATE_LIMIT]. */
        public const val RATE_LIMIT_ENV: String = "STUDIO_ASSIGN_RATE_LIMIT"

        /** Values of `SDUI_ENV` (case-insensitive) that tolerate a missing service token. */
        public val DEV_ENVS: Set<String> = setOf("dev", "development", "local", "test")

        /**
         * Resolve the effective service token. Secure by default, mirroring
         * [dev.sdui.kmp.studio.server.auth.StudioJwt.resolveSecret]: a configured token is always
         * honored; a missing token is tolerated only in an explicit dev environment and otherwise
         * throws so a misconfigured production deploy cannot silently expose the assign route
         * (a stored-ReDoS + unbounded-write vector) to anonymous callers.
         *
         * @throws IllegalStateException if no token is configured outside a dev environment.
         */
        public fun resolveToken(configured: String?, sduiEnv: String?): String? {
            val token = configured?.takeIf { it.isNotBlank() }
            if (token != null) return token
            check(sduiEnv?.lowercase() in DEV_ENVS) {
                "$TOKEN_ENV is not set. Refusing to start: /screens/{id}/assign would be open to " +
                    "anonymous callers, which is a stored-ReDoS and unbounded-write vector. Set " +
                    "$TOKEN_ENV to a strong random value, or set SDUI_ENV to one of $DEV_ENVS for " +
                    "local development only."
            }
            System.err.println(
                "[studio-server] $TOKEN_ENV not set — /screens/{id}/assign is UNAUTHENTICATED " +
                    "because SDUI_ENV=$sduiEnv. Never use this outside local development.",
            )
            return null
        }

        /** Build an [AssignRouteConfig] from the process environment. See [resolveToken]. */
        public fun fromEnv(): AssignRouteConfig = AssignRouteConfig(
            serviceToken = resolveToken(System.getenv(TOKEN_ENV), System.getenv("SDUI_ENV")),
            requestsPerMinute = System.getenv(RATE_LIMIT_ENV)?.toIntOrNull() ?: DEFAULT_ASSIGN_RATE_LIMIT,
        )
    }
}

/**
 * Mount the service-to-service `/screens/{id}/assign` route used by the sample-server's
 * `StudioAssignmentClient`. Reads `X-Sdui-Client-Id` and any `X-Sdui-Context-*` headers, runs
 * the assignment service, returns the body as JSON.
 *
 * Hardened surface (audit #6):
 *  - A [RateLimitPlugin] caps requests per caller so an attacker cannot flood distinct client ids
 *    to force unbounded `experiment_assignments` writes or amplify audience-regex evaluation.
 *  - When [AssignRouteConfig.serviceToken] is set, every request must present a matching
 *    `X-Sdui-Service-Token` header (compared in constant time) or receive `401`. Writes are thus
 *    gated behind a trusted service credential rather than open to anonymous callers.
 *
 * Assignment persistence remains idempotent per `(experimentId, clientId)` — a repeated call for
 * the same client reuses the existing sticky row and never inserts a second one.
 */
public fun Route.installScreenAssignRoute(
    service: AssignmentService = AssignmentService(),
    config: AssignRouteConfig = AssignRouteConfig.fromEnv(),
) {
    route("/screens/{id}/assign") {
        install(RateLimitPlugin) {
            requestsPerMinute = config.requestsPerMinute
        }
        get {
            if (!requireServiceToken(call, config)) return@get
            val screenId = call.parameters["id"].orEmpty()
            val clientId = call.request.headers["X-Sdui-Client-Id"]?.takeIf { it.isNotBlank() }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing X-Sdui-Client-Id"))
                    return@get
                }
            val context = buildContext(call)
            val result = service.assign(screenId, clientId, context)
            if (result.bodyJson == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no version available for $screenId"))
                return@get
            }
            val parsed = runCatching { SduiJson.parseToJsonElement(result.bodyJson) }.getOrNull()
            call.respond(
                HttpStatusCode.OK,
                AssignResponse(
                    screenId = screenId,
                    experimentId = result.experimentId,
                    variantId = result.variantId,
                    screenVersionId = result.screenVersionId?.toString(),
                    reason = result.reason.wire,
                    body = parsed,
                ),
            )
        }
    }
}

/**
 * Enforce the service-token gate. Returns true when the call may proceed; otherwise responds with
 * `401` and returns false. A `null` configured token means the route is running in open dev mode.
 */
private suspend fun requireServiceToken(call: ApplicationCall, config: AssignRouteConfig): Boolean {
    val expected = config.serviceToken ?: return true
    val provided = call.request.headers[AssignRouteConfig.SERVICE_TOKEN_HEADER]
    if (provided != null && constantTimeEquals(provided, expected)) return true
    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("missing or invalid service token"))
    return false
}

// Constant-time string comparison so token verification does not leak length/prefix via timing.
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

private const val CONTEXT_HEADER_PREFIX = "X-Sdui-Context-"
private const val MAX_WEIGHT = 100

private fun buildContext(call: ApplicationCall): Map<String, String> {
    val out = mutableMapOf<String, String>()
    call.request.headers.forEach { name, values ->
        if (name.startsWith(CONTEXT_HEADER_PREFIX, ignoreCase = true)) {
            val key = name.substring(CONTEXT_HEADER_PREFIX.length).lowercase()
            val v = values.firstOrNull().orEmpty()
            if (v.isNotEmpty()) out[key] = v
        }
    }
    return out
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrBadRequest(): T? = try {
    receive<T>()
} catch (_: SerializationException) {
    respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request body"))
    null
} catch (_: io.ktor.server.plugins.ContentTransformationException) {
    respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request body"))
    null
}

// Disallow funky characters so experiment ids stay URL-safe.
private val EXPERIMENT_ID_PATTERN = Regex("""[A-Za-z0-9_\-]{1,128}""")

private fun validateExperimentId(id: String): Boolean = EXPERIMENT_ID_PATTERN.matches(id)

private fun ExperimentRow.toDto(): ExperimentDto = ExperimentDto(
    id = id,
    screenId = screenId,
    name = name,
    description = description,
    status = status.wire,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    createdBy = createdBy.toString(),
)

private fun VariantRow.toDto(): VariantDto = VariantDto(
    id = id,
    experimentId = experimentId,
    name = name,
    weight = weight,
    screenVersionId = screenVersionId.toString(),
    createdAt = createdAt.toString(),
)

private fun AudienceRow.toDto(): AudienceDto = AudienceDto(
    id = id,
    name = name,
    description = description,
    predicate = predicate,
    createdAt = createdAt.toString(),
)
