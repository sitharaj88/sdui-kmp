package dev.sdui.kmp.auth.rs256

import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class Rs256JwtTest {

    @Test
    fun issue_then_verify_round_trips() {
        val keyPair = generateRsaKeyPair()
        val issuer = Rs256JwtIssuer(
            keyPair = keyPair,
            keyId = "k1",
            issuer = "test-iss",
            audience = "test-aud",
            expiry = 5.minutes,
        )
        val verifier = Rs256JwtVerifier(
            publicKey = issuer.publicKey,
            issuer = "test-iss",
            audience = "test-aud",
        )
        val token = issuer.issue(subject = "user-1", claims = mapOf("role" to "admin"))
        val decoded = verifier.verifier().verify(token)
        assertEquals("user-1", decoded.subject)
        assertEquals("test-iss", decoded.issuer)
        assertEquals("admin", decoded.getClaim("role").asString())
        assertEquals("k1", decoded.keyId)
        assertNotNull(decoded.id, "jti claim must be present")
    }

    @Test
    fun mismatched_public_key_fails_verification() {
        val signerKeys = generateRsaKeyPair()
        val attackerKeys = generateRsaKeyPair()
        val issuer = Rs256JwtIssuer(signerKeys, "k1", "iss", "aud", 5.minutes)
        val token = issuer.issue("user-1")
        val verifier = Rs256JwtVerifier(attackerKeys.public as java.security.interfaces.RSAPublicKey, "iss", "aud")
        assertFailsWith<SignatureVerificationException> {
            verifier.verifier().verify(token)
        }
    }

    @Test
    fun expired_token_is_rejected() {
        val keyPair = generateRsaKeyPair()
        val issuer = Rs256JwtIssuer(keyPair, "k1", "iss", "aud", 1.milliseconds)
        val token = issuer.issue("user-1")
        // 1ms expiry + clock leeway 0 — wait deterministically.
        Thread.sleep(50)
        val verifier = Rs256JwtVerifier(issuer.publicKey, "iss", "aud")
        assertFailsWith<TokenExpiredException> {
            verifier.verifier().verify(token)
        }
    }
}
