package dev.sdui.kmp.auth.rs256

import org.slf4j.LoggerFactory

/**
 * SAM contract for fetching named secrets at runtime. Implementations decide where the secret
 * lives — environment variables, HashiCorp Vault, AWS Secrets Manager, Kubernetes Secrets,
 * etc. — and the rest of `:auth-rs256` reads through this single seam.
 *
 * Key conventions used by `:auth-rs256`:
 *  - `RSA_PRIVATE_KEY` — PEM-encoded PKCS#8 private key (`-----BEGIN PRIVATE KEY-----` ... ).
 *  - `RSA_PUBLIC_KEY`  — PEM-encoded X.509 SubjectPublicKeyInfo (`-----BEGIN PUBLIC KEY-----`).
 *  - `RSA_KEY_ID`      — opaque key identifier published in the JWKS `kid` field.
 *
 * Returning `null` means "secret not present" — callers fall back to a sane default (or fail
 * loudly if the secret is required).
 */
public fun interface SecretsProvider {
    /** Returns the secret stored under [key], or `null` if no value is configured. */
    public fun get(key: String): String?
}

/**
 * Default [SecretsProvider] backed by `System.getenv`. Suitable for containerised deployments
 * where secrets are mounted as environment variables (Kubernetes secret env-from, Docker
 * `--env-file`, etc.) and for local development.
 *
 * Reads are uncached because environment-variable lookup is already an in-process map access.
 */
public class EnvSecretsProvider : SecretsProvider {
    override fun get(key: String): String? = System.getenv(key)
}

/**
 * Production wiring stub for [HashiCorp Vault](https://www.vaultproject.io/). The real
 * integration shape is:
 *
 *  1. POST/GET `${vaultAddress}/v1/${vaultPath}/${key}` with header `X-Vault-Token: ${vaultToken}`.
 *  2. Parse the JSON response body — Vault returns `{"data": {"data": {"<key>": "<value>"}}}`
 *     for KV v2 mounts, or `{"data": {"<key>": "<value>"}}` for KV v1.
 *  3. Surface transport / 5xx errors as a retryable signal so the caller can back off; surface
 *     404 / `errors: []` as `null` (matching the [SecretsProvider.get] contract).
 *  4. Cache decoded secrets in-memory for a short TTL (typically 30–60s) so a single login
 *     burst doesn't fan out to Vault per call.
 *
 * This stub is intentionally minimal: it logs a TODO line and falls back to environment-variable
 * lookup so dev/CI flows that don't yet have Vault wired up still function. **Do not ship this
 * to production.**
 */
public class VaultSecretsProvider(
    /** Vault HTTP address, e.g. `https://vault.example.internal:8200`. */
    public val vaultAddress: String,
    /** Token used in the `X-Vault-Token` header. Use a short-lived AppRole token in prod. */
    public val vaultToken: String,
    /** KV path to read from, e.g. `secret/data/sdui/prod`. */
    public val vaultPath: String,
    /** Fallback used when this stub is asked for a secret it doesn't yet know how to fetch. */
    private val fallback: SecretsProvider = EnvSecretsProvider(),
) : SecretsProvider {

    private val logger = LoggerFactory.getLogger(VaultSecretsProvider::class.java)

    override fun get(key: String): String? {
        logger.warn(
            "TODO: integrate with HashiCorp Vault HTTP API — would GET {}/v1/{}/{} with X-Vault-Token; falling back to env",
            vaultAddress.trimEnd('/'),
            vaultPath.trim('/'),
            key,
        )
        return fallback.get(key)
    }
}
