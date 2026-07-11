package dev.sdui.kmp.auth.rs256

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import java.security.interfaces.RSAPublicKey

/**
 * Verifies RS256 JWTs signed by an [Rs256JwtIssuer] (or any peer with the matching public key).
 *
 * @property publicKey the RSA public half. Mismatch with the signer's private key surfaces as
 *   `SignatureVerificationException` from [verifier].
 * @property issuer expected `iss` claim. Tokens with a different issuer are rejected.
 * @property audience expected `aud` claim. Tokens with a different audience are rejected.
 *
 * Construct once at server boot — `JWTVerifier` instances are immutable and thread-safe.
 */
public class Rs256JwtVerifier(
    public val publicKey: RSAPublicKey,
    public val issuer: String,
    public val audience: String,
) {
    /**
     * Build a fresh `JWTVerifier`. The Auth0 type already encapsulates expiration, signature,
     * and standard claim checks; callers wire it into Ktor's `jwt { verifier(...) }`.
     */
    public fun verifier(): JWTVerifier =
        JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer(issuer)
            .withAudience(audience)
            .acceptLeeway(0)
            .build()
}
