package dev.sdui.kmp.studio.server.auth

import dev.sdui.kmp.studio.server.db.EditorRole
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.Principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import java.util.UUID

/**
 * Resolved-from-JWT editor identity. Routes consume this rather than [JWTPrincipal] directly
 * so role checks stay type-safe.
 */
public data class EditorPrincipal(
    public val editorId: UUID,
    public val email: String,
    public val role: EditorRole,
    public val sessionId: UUID,
) : Principal {
    public companion object {
        /**
         * Resolve from the underlying [JWTPrincipal]. Returns null if any required claim is
         * missing or malformed. The early-return chain reads more clearly than the equivalent
         * nested `?.let { }` cascade for six independent guards.
         */
        @Suppress("ReturnCount")
        public fun fromJwt(jwt: JWTPrincipal): EditorPrincipal? {
            val payload = jwt.payload
            val sub = payload.subject ?: return null
            val email = payload.getClaim(StudioJwt.CLAIM_EMAIL)?.asString() ?: return null
            val role = payload.getClaim(StudioJwt.CLAIM_ROLE)?.asString() ?: return null
            val jti = payload.id ?: return null
            val editorId = parseUuid(sub) ?: return null
            val sessionId = parseUuid(jti) ?: return null
            return EditorPrincipal(editorId, email, EditorRole.parse(role), sessionId)
        }

        private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()
    }
}

/** Extract the [EditorPrincipal] from the call. Returns null if the call is unauthenticated. */
public fun ApplicationCall.editorPrincipal(): EditorPrincipal? =
    principal<JWTPrincipal>()?.let(EditorPrincipal::fromJwt)
