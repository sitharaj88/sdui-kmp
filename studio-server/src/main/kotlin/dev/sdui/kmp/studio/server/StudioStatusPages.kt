package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import java.sql.SQLException

/**
 * Installs the Studio control plane's uniform error contract via Ktor's [StatusPages].
 *
 * Before this plugin existed, an exception escaping a route handler — most importantly a
 * store-layer race under a unique index (two concurrent publishes colliding on
 * `screen_versions_screen_version_uq`, a duplicate variant/experiment id, a sticky-assignment
 * PK clash) — surfaced as a bare `500` with an inconsistent, non-JSON body, defeating the
 * [ErrorResponse] contract the routes use on their own happy/validation paths.
 *
 * Every branch responds with the standard [ErrorResponse] envelope so clients get one shape
 * regardless of which layer failed. The catch-all never echoes the exception message or stack
 * trace to the client (it is logged server-side with the request id from
 * [StudioRequestIdPlugin]'s MDC), so an internal failure cannot leak implementation detail.
 *
 * Mappings, most specific first:
 *
 *  - [BadRequestException] / [ContentTransformationException] -> `400` — a malformed or
 *    undecodable request body that reached a handler which did not translate it itself.
 *  - [IllegalArgumentException] -> `400` — a `require(...)` failure (e.g. a referenced screen or
 *    screen-version does not exist) that was not already caught at the route boundary.
 *  - [IllegalStateException] -> `409` — a domain race expressed via `error(...)` in the store
 *    (e.g. a draft consumed by a concurrent publish, a source version that vanished mid-revert).
 *  - [SQLException] (which Exposed's `ExposedSQLException` extends) -> `409` when it is an
 *    integrity-constraint violation (SQLState class `23`, which both Postgres and H2 use for
 *    unique/PK conflicts — the deterministic outcome of a concurrent create/publish race),
 *    otherwise `500`.
 *  - [Throwable] -> `500` with a stable, detail-free body.
 */
public fun Application.installStudioStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request body"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "invalid request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "conflicting request"))
        }
        exception<SQLException> { call, cause ->
            if (cause.isConstraintViolation()) {
                call.application.environment.log.debug("constraint violation -> 409: {}", cause.message)
                call.respond(HttpStatusCode.Conflict, ErrorResponse("resource already exists or conflicts"))
            } else {
                call.application.environment.log.error("unhandled SQL error", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
            }
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
}

/**
 * True when this exception (or anything in its cause chain) is a SQL integrity-constraint
 * violation. SQLState class `23` covers unique/primary-key/foreign-key/not-null conflicts in
 * both Postgres and H2; `23505` specifically is the unique-violation the concurrent
 * create/publish paths race into. We walk the chain because Exposed nests the driver's
 * [SQLException] as the cause.
 */
private fun SQLException.isConstraintViolation(): Boolean =
    generateSequence(this as Throwable) { it.cause }
        .any { (it as? SQLException)?.sqlState?.startsWith("23") == true }
