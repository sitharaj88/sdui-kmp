package dev.sdui.kmp.sample.server.db

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Installs the `/readiness` endpoint.
 *
 *  - Returns `200 OK` with `{"ready":true}` when the database probe succeeds and the JWT
 *    signing key is available.
 *  - Returns `503 Service Unavailable` with a structured `{"ready":false,"reason":"..."}`
 *    payload otherwise.
 *
 * `/health` (defined in `Main.kt`) stays simple and always returns 200 — that's the liveness
 * check. `/readiness` is what k8s and the load balancer should hit.
 */
public fun Routing.installReadinessRoute() {
    get("/readiness") {
        val dbError = Db.probe()
        val jwtError = if (JWT_KEY_LOADED) null else "jwt signing key missing"
        val errors = listOfNotNull(
            dbError?.let { "db: $it" },
            jwtError,
        )
        if (errors.isEmpty()) {
            call.respondText(
                """{"ready":true,"fallback_db":${Db.isFallback}}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        } else {
            val reasonsJson = errors.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
            call.respondText(
                """{"ready":false,"reasons":[$reasonsJson]}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}

/**
 * Whether the JWT signing key is loaded. The sample uses a hard-coded HMAC secret which is
 * always present at compile time, so this is effectively a smoke check; production would
 * verify a key loaded from a secrets manager.
 */
private const val JWT_KEY_LOADED: Boolean = true
