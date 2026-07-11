package dev.sdui.kmp.auth.rs256

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Mounts a JWKS endpoint at [path] (default `/.well-known/jwks.json`) that publishes the
 * issuer's public key so clients (and federated services) can verify tokens without the
 * private key ever leaving the server.
 *
 * Output format follows [RFC 7517](https://datatracker.ietf.org/doc/html/rfc7517):
 *
 * ```
 * { "keys": [ { "kty": "RSA", "use": "sig", "alg": "RS256",
 *               "kid": "<keyId>", "n": "<base64url-modulus>", "e": "<base64url-exponent>" } ] }
 * ```
 *
 * The route is unauthenticated by design — JWKS is meant to be public.
 */
public fun Route.installJwksEndpoint(
    path: String = "/.well-known/jwks.json",
    issuer: Rs256JwtIssuer,
) {
    val body = encodeJwks(issuer)
    get(path) {
        call.respondText(body, ContentType.Application.Json)
    }
}

internal fun encodeJwks(issuer: Rs256JwtIssuer): String {
    val key = issuer.publicKey
    val keys: JsonArray = buildJsonArray {
        add(jwkOf(key, issuer.keyId))
    }
    val doc: JsonObject = buildJsonObject { put("keys", keys) }
    return doc.toString()
}

private fun jwkOf(key: RSAPublicKey, keyId: String): JsonObject = buildJsonObject {
    put("kty", JsonPrimitive("RSA"))
    put("use", JsonPrimitive("sig"))
    put("alg", JsonPrimitive("RS256"))
    put("kid", JsonPrimitive(keyId))
    put("n", JsonPrimitive(base64UrlUnsignedBigInt(key.modulus)))
    put("e", JsonPrimitive(base64UrlUnsignedBigInt(key.publicExponent)))
}

/**
 * Encode a non-negative `BigInteger` as base64url with no padding, per RFC 7518 §6.3.1.
 *
 * `BigInteger.toByteArray` returns a two's-complement signed representation; for positive
 * values the high bit is sometimes a leading zero byte. RFC 7518 says the unsigned magnitude
 * must be encoded with no leading zeros, so we strip that byte when present.
 */
private fun base64UrlUnsignedBigInt(value: BigInteger): String {
    val raw = value.toByteArray()
    val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned)
}
