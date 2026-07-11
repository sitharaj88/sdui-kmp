package dev.sdui.kmp.studio.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import dev.sdui.kmp.studio.server.db.EditorAccount
import java.util.Date

/**
 * JWT issuance / verification for Studio editors.
 *
 * **Sample-only.** This issuer uses HMAC256 with a hard-coded fallback secret so the studio
 * runs out of the box for local development. A parallel agent is hardening the sample-server's
 * auth to RS256 in `:auth-rs256`; once that lands, swap [Algorithm.HMAC256] here for that
 * module's `Rs256JwtIssuer` and remove the literal secret. The wire shape (claims, realm,
 * issuer string) was chosen to match the planned RS256 format so the swap is a one-line edit.
 *
 * Claims:
 *  - `sub` — the editor's UUID (string).
 *  - `email` — the editor's email, for human-readable audit logs.
 *  - `role` — `admin` / `editor` / `viewer`. Used by route role guards.
 *  - `jti` — the [dev.sdui.kmp.studio.server.db.EditorSessions] row id, so revocation works.
 */
public class StudioJwt(secret: String) {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    /** Build a verifier for the JWT auth provider. */
    public fun verifier(): JWTVerifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    /** Mint a token for [account] tied to [sessionId] (the [jti] claim). */
    public fun issue(account: EditorAccount, sessionId: java.util.UUID, expiresAt: java.time.Instant): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(account.id.toString())
            .withClaim(CLAIM_EMAIL, account.email)
            .withClaim(CLAIM_ROLE, account.role.wire)
            .withJWTId(sessionId.toString())
            .withIssuedAt(Date())
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)

    public companion object {
        public const val ISSUER: String = "sdui-kmp-studio"
        public const val AUDIENCE: String = "sdui-kmp-studio-clients"
        public const val REALM: String = "sdui-kmp-studio"

        /** Custom claim names. */
        public const val CLAIM_EMAIL: String = "email"
        public const val CLAIM_ROLE: String = "role"

        /**
         * Insecure development-only fallback secret. Used **only** when `STUDIO_JWT_SECRET` is
         * unset AND `SDUI_ENV` names a development environment (see [DEV_ENVS]). In any other
         * environment [resolveSecret] fails fast rather than signing with a public key.
         */
        public const val FALLBACK_SECRET: String = "studio-only-not-a-real-secret-please-rotate"

        /** Minimum length for a real `STUDIO_JWT_SECRET`; short keys weaken HMAC256 unacceptably. */
        public const val MIN_SECRET_LENGTH: Int = 32

        /** Values of `SDUI_ENV` (case-insensitive) that permit the insecure [FALLBACK_SECRET]. */
        public val DEV_ENVS: Set<String> = setOf("dev", "development", "local", "test")

        /**
         * Resolve the effective signing secret. Secure by default: a strong `STUDIO_JWT_SECRET`
         * is always honored; a missing secret is only tolerated in an explicit dev environment
         * and otherwise throws so a misconfigured production deploy cannot silently sign
         * admin/publish/RBAC tokens with the public [FALLBACK_SECRET].
         *
         * Extracted as a pure function of its inputs so the policy is unit-testable without
         * mutating process environment.
         *
         * @param configured the raw `STUDIO_JWT_SECRET` value (may be null/blank).
         * @param sduiEnv the raw `SDUI_ENV` value (may be null).
         * @throws IllegalArgumentException if a configured secret is shorter than [MIN_SECRET_LENGTH].
         * @throws IllegalStateException if no secret is configured outside a dev environment.
         */
        public fun resolveSecret(configured: String?, sduiEnv: String?): String {
            val secret = configured?.takeIf { it.isNotBlank() }
            if (secret != null) {
                require(secret.length >= MIN_SECRET_LENGTH) {
                    "STUDIO_JWT_SECRET must be at least $MIN_SECRET_LENGTH characters " +
                        "(got ${secret.length})."
                }
                return secret
            }
            check(sduiEnv?.lowercase() in DEV_ENVS) {
                "STUDIO_JWT_SECRET is not set. Refusing to start: with the public fallback " +
                    "signing secret, anyone could forge admin/editor JWTs across the entire " +
                    "publish and RBAC surface. Set STUDIO_JWT_SECRET to a strong random value " +
                    "(>= $MIN_SECRET_LENGTH chars), or set SDUI_ENV to one of $DEV_ENVS for " +
                    "local development only."
            }
            System.err.println(
                "[studio-server] STUDIO_JWT_SECRET not set — using the INSECURE development " +
                    "fallback secret because SDUI_ENV=$sduiEnv. Tokens are forgeable. Never use " +
                    "this outside local development.",
            )
            return FALLBACK_SECRET
        }

        /**
         * Build a [StudioJwt] from the process environment (`STUDIO_JWT_SECRET`, `SDUI_ENV`).
         * See [resolveSecret] for the security policy.
         */
        public fun fromEnv(): StudioJwt =
            StudioJwt(resolveSecret(System.getenv("STUDIO_JWT_SECRET"), System.getenv("SDUI_ENV")))
    }
}
