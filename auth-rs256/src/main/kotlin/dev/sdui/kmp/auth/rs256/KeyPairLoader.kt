package dev.sdui.kmp.auth.rs256

import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Default RSA key size when generating an in-memory pair. 2048 bits is the modern minimum
 * recommended by NIST SP 800-57 / RFC 7518 for RS256.
 */
private const val DEFAULT_RSA_KEY_BITS: Int = 2048

/** Secrets-provider key for the PEM-encoded private key (PKCS#8). */
public const val SECRET_KEY_RSA_PRIVATE: String = "RSA_PRIVATE_KEY"

/** Secrets-provider key for the PEM-encoded public key (X.509 SubjectPublicKeyInfo). */
public const val SECRET_KEY_RSA_PUBLIC: String = "RSA_PUBLIC_KEY"

/**
 * Reads `RSA_PRIVATE_KEY` and `RSA_PUBLIC_KEY` from [provider] (PEM-encoded — PKCS#8 private,
 * X.509 public) and returns a [KeyPair]. If either secret is missing, an in-memory 2048-bit
 * RSA pair is generated and a stark warning is logged so deployments that forgot to wire
 * keys notice immediately.
 *
 * Generated keys do not survive process restart — issued tokens become unverifiable on the
 * next boot. This is acceptable for local development only; production must always hit the
 * configured secrets store.
 */
public fun loadOrGenerateKeyPair(provider: SecretsProvider): KeyPair {
    val logger = LoggerFactory.getLogger("dev.sdui.kmp.auth.rs256.KeyPairLoader")
    val privatePem = provider.get(SECRET_KEY_RSA_PRIVATE)
    val publicPem = provider.get(SECRET_KEY_RSA_PUBLIC)
    if (privatePem.isNullOrBlank() || publicPem.isNullOrBlank()) {
        logger.warn(
            "RSA_PRIVATE_KEY / RSA_PUBLIC_KEY not configured — generating an EPHEMERAL " +
                "$DEFAULT_RSA_KEY_BITS-bit key pair. Tokens will NOT verify after restart. " +
                "Configure your SecretsProvider before going to production.",
        )
        return generateRsaKeyPair()
    }
    return KeyPair(parsePublicPem(publicPem), parsePrivatePem(privatePem))
}

/** Generates a fresh in-memory [KeyPair] suitable for RS256 signing. Visible for tests. */
internal fun generateRsaKeyPair(bits: Int = DEFAULT_RSA_KEY_BITS): KeyPair {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(bits)
    return gen.generateKeyPair()
}

private fun parsePrivatePem(pem: String): java.security.interfaces.RSAPrivateKey {
    val bytes = decodePem(pem, "PRIVATE KEY")
    val spec = PKCS8EncodedKeySpec(bytes)
    val key = KeyFactory.getInstance("RSA").generatePrivate(spec)
    return key as java.security.interfaces.RSAPrivateKey
}

private fun parsePublicPem(pem: String): java.security.interfaces.RSAPublicKey {
    val bytes = decodePem(pem, "PUBLIC KEY")
    val spec = X509EncodedKeySpec(bytes)
    val key = KeyFactory.getInstance("RSA").generatePublic(spec)
    return key as java.security.interfaces.RSAPublicKey
}

/**
 * Strip PEM armor (`-----BEGIN <kind>-----` / `-----END <kind>-----`) and base64-decode the
 * body. Whitespace inside the armored body is tolerated — most exporters wrap at 64 columns.
 *
 * Deliberately implemented by hand: BouncyCastle's PEMParser would pull in another ~5 MB of
 * dependencies for what is a 10-line transformation. JDK 8+ ships [Base64] which handles the
 * decoding; the marker stripping is trivial.
 */
private fun decodePem(pem: String, kind: String): ByteArray {
    val begin = "-----BEGIN $kind-----"
    val end = "-----END $kind-----"
    val trimmed = pem.trim()
    val startIdx = trimmed.indexOf(begin)
    val endIdx = trimmed.indexOf(end)
    require(startIdx >= 0 && endIdx > startIdx) {
        "Expected PEM block of type '$kind' but found neither marker"
    }
    val body = trimmed.substring(startIdx + begin.length, endIdx)
        .replace("\r", "")
        .replace("\n", "")
        .replace(" ", "")
    return Base64.getDecoder().decode(body)
}
