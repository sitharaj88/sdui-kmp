package dev.sdui.kmp.auth.rs256

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.math.BigInteger
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JwksRouteTest {

    @Test
    fun jwks_endpoint_publishes_matching_public_key() = testApplication {
        val keyPair = generateRsaKeyPair()
        val issuer = Rs256JwtIssuer(keyPair, "test-kid", "iss", "aud", 5.minutes)
        application {
            routing { installJwksEndpoint(issuer = issuer) }
        }
        val response = client.get("/.well-known/jwks.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val doc: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val keys = doc["keys"]?.jsonArray
        assertNotNull(keys, "JWKS document must contain a 'keys' array")
        assertEquals(1, keys.size)
        val jwk = keys[0].jsonObject
        assertEquals("RSA", jwk["kty"]?.jsonPrimitive?.content)
        assertEquals("RS256", jwk["alg"]?.jsonPrimitive?.content)
        assertEquals("sig", jwk["use"]?.jsonPrimitive?.content)
        assertEquals("test-kid", jwk["kid"]?.jsonPrimitive?.content)

        val n = jwk["n"]?.jsonPrimitive?.content
        val e = jwk["e"]?.jsonPrimitive?.content
        assertNotNull(n)
        assertNotNull(e)
        // The published modulus / exponent must round-trip back to the issuer's RSA public key.
        val decodedN = BigInteger(1, Base64.getUrlDecoder().decode(n))
        val decodedE = BigInteger(1, Base64.getUrlDecoder().decode(e))
        assertEquals(issuer.publicKey.modulus, decodedN)
        assertEquals(issuer.publicKey.publicExponent, decodedE)
    }
}
