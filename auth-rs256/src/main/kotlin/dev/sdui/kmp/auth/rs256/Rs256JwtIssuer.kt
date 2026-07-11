package dev.sdui.kmp.auth.rs256

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Issues RS256-signed JWTs. Stateless — the underlying [Algorithm] holds the key pair and is
 * thread-safe.
 *
 * @property keyPair RSA key pair. The private half signs; the public half feeds [Rs256JwtVerifier]
 *   and the JWKS endpoint.
 * @property keyId opaque key id published in both the JWT `kid` header and the JWKS document.
 *   Used by clients to pick the right verification key during rotation.
 * @property issuer mandatory `iss` claim — typically your service's stable identifier.
 * @property audience mandatory `aud` claim — typically your client's stable identifier.
 * @property expiry default token lifetime; callers may not override per-token in this minimal
 *   API. Adjust the issuer instance if a longer/shorter window is needed.
 */
public class Rs256JwtIssuer(
    public val keyPair: KeyPair,
    public val keyId: String,
    public val issuer: String,
    public val audience: String,
    public val expiry: Duration,
) {
    /**
     * The RSA public key half of [keyPair]. Exposed for [Rs256JwtVerifier] and the JWKS route
     * so callers don't need to cast manually.
     */
    public val publicKey: RSAPublicKey = keyPair.public as RSAPublicKey

    private val algorithm: Algorithm =
        Algorithm.RSA256(keyPair.public as RSAPublicKey, keyPair.private as RSAPrivateKey)

    /**
     * Issue a fresh signed JWT. The `jti` claim defaults to a freshly minted [UUID]; callers
     * that need a server-tracked session id (revocation, audit) pass the desired id via
     * [claims] under the `jti` key, which overrides the default.
     *
     * [claims] values must be of a type the underlying `java-jwt` builder accepts:
     * `String`, `Boolean`, `Int`, `Long`, `Double`, `Date`, or [UUID] (auto-stringified).
     * Unsupported types raise an `IllegalArgumentException` at issue time.
     */
    public fun issue(subject: String, claims: Map<String, Any> = emptyMap()): String {
        val now = System.currentTimeMillis()
        val jti = (claims[CLAIM_JTI] as? String) ?: (claims[CLAIM_JTI] as? UUID)?.toString()
            ?: UUID.randomUUID().toString()
        val builder = JWT.create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(subject)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + expiry.toJavaDuration().toMillis()))
            .withJWTId(jti)
        claims.forEach { (k, v) ->
            // jti is already applied via withJWTId; skip to avoid the registered-claim collision
            // Auth0's java-jwt would otherwise raise from withClaim("jti", ...).
            if (k != CLAIM_JTI) applyClaim(builder, k, v)
        }
        return builder.sign(algorithm)
    }

    public companion object {
        /** Reserved claim name for the JWT id (RFC 7519 §4.1.7). */
        public const val CLAIM_JTI: String = "jti"
    }
}

private fun applyClaim(
    builder: com.auth0.jwt.JWTCreator.Builder,
    key: String,
    value: Any,
) {
    when (value) {
        is String -> builder.withClaim(key, value)
        is Boolean -> builder.withClaim(key, value)
        is Int -> builder.withClaim(key, value)
        is Long -> builder.withClaim(key, value)
        is Double -> builder.withClaim(key, value)
        is Date -> builder.withClaim(key, value)
        is UUID -> builder.withClaim(key, value.toString())
        else -> throw IllegalArgumentException(
            "Unsupported JWT claim type for key '$key': ${value.javaClass.name}",
        )
    }
}
