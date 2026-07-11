package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.auth.rs256.RateLimitPlugin
import dev.sdui.kmp.studio.server.auth.StudioJwt
import dev.sdui.kmp.studio.server.auth.editorPrincipal
import dev.sdui.kmp.studio.server.db.EditorAccountStore
import dev.sdui.kmp.studio.server.db.EditorSessionStore
import dev.sdui.kmp.studio.server.model.ErrorResponse
import dev.sdui.kmp.studio.server.model.LoginRequest
import dev.sdui.kmp.studio.server.model.LoginResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException

/**
 * Requests-per-minute cap applied per source IP on `/admin/auth/login`. Coarse defence-in-depth
 * behind the finer-grained [LoginRateLimiter] (IP + email); it also shields bcrypt from an
 * attacker rotating emails through a single IP, which the per-pair limiter would not catch.
 */
public const val LOGIN_REQUESTS_PER_MINUTE: Int = 15

/**
 * Login / logout routes for the Studio. Mounts under `/admin/auth/`.
 *
 * Login is unauthenticated by design — it's where authentication starts. Logout requires a
 * valid token because it revokes the session row.
 *
 * Login is guarded on two axes so credential stuffing cannot turn each bcrypt verify into a
 * ~250 ms CPU DoS amplifier:
 *
 *  1. A per-source-IP [RateLimitPlugin] caps total attempts per minute. Because it short-circuits
 *     the pipeline (see `ShortCircuitPhase`), a throttled request never reaches the handler and
 *     mints no session.
 *  2. A per-`(IP + email)` [LoginRateLimiter] with progressive back-off is consulted inside the
 *     handler — after the body is parsed but before the password is verified — so a locked-out
 *     pair never reaches bcrypt or [EditorSessionStore.issue].
 *
 * @param loginRateLimiter per-`(IP + email)` lockout store. Defaults to a fresh in-memory
 *   instance per module boot; tests inject a configured one.
 */
public fun Route.installEditorAuthRoutes(
    jwt: StudioJwt,
    jwtAuthName: String,
    loginRateLimiter: LoginRateLimiter = LoginRateLimiter(),
) {
    route("/admin/auth/login") {
        install(RateLimitPlugin) {
            requestsPerMinute = LOGIN_REQUESTS_PER_MINUTE
        }
        post {
            val req = try {
                call.receive<LoginRequest>()
            } catch (_: SerializationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed login request"))
                return@post
            }
            if (req.email.isBlank() || req.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("email and password are required"))
                return@post
            }
            val email = req.email.trim().lowercase()
            val throttleKey = loginRateLimiter.key(call.actorIp(), email)
            val retryAfter = loginRateLimiter.retryAfterSeconds(throttleKey)
            if (retryAfter != null) {
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("too many failed login attempts; retry after ${retryAfter}s"),
                )
                return@post
            }
            val account = EditorAccountStore.authenticate(email, req.password)
            if (account == null) {
                loginRateLimiter.recordFailure(throttleKey)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials"))
                return@post
            }
            loginRateLimiter.recordSuccess(throttleKey)
            val session = EditorSessionStore.issue(account.id)
            val token = jwt.issue(account, session.id, session.expiresAt)
            EditorAccountStore.recordLogin(account.id)
            call.respond(
                HttpStatusCode.OK,
                LoginResponse(
                    token = token,
                    expiresAt = session.expiresAt.toString(),
                    role = account.role.wire,
                ),
            )
        }
    }

    authenticate(jwtAuthName) {
        post("/admin/auth/logout") {
            val principal = call.editorPrincipal()
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid principal"))
                return@post
            }
            EditorSessionStore.revoke(principal.sessionId)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}
