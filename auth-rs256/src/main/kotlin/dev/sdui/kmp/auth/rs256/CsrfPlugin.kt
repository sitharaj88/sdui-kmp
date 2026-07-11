package dev.sdui.kmp.auth.rs256

import io.ktor.http.Cookie
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText
import java.security.SecureRandom
import java.util.Base64

/**
 * Default cookie name carrying the CSRF token. Not httpOnly so JavaScript on the page can
 * read it and copy the value into the [DEFAULT_HEADER_NAME] header. SameSite=Lax keeps the
 * cookie out of cross-site POSTs entirely; the double-submit check is the second line of
 * defence behind the SameSite primary.
 */
public const val DEFAULT_COOKIE_NAME: String = "csrf-token"

/** Default header name the client must echo with the cookie value on mutating requests. */
public const val DEFAULT_HEADER_NAME: String = "X-CSRF-Token"

/** Configuration for [CsrfPlugin]. See its KDoc for the threat model. */
public class CsrfConfig {
    /** Name of the double-submit cookie issued on mint. */
    public var cookieName: String = DEFAULT_COOKIE_NAME

    /** Name of the request header the client must echo on mutating requests. */
    public var headerName: String = DEFAULT_HEADER_NAME

    /**
     * Predicate selecting which GET responses should mint a token. Default: paths starting
     * with `/screens/`. Tokens minted on a non-matching path are not auto-issued — clients
     * are expected to call a mint-eligible URL on first load.
     */
    public var mintOn: (ApplicationCall) -> Boolean = { call ->
        call.request.local.uri.startsWith("/screens/")
    }

    /**
     * Predicate selecting which mutating requests must present a matching token. Default:
     * any non-safe HTTP method. Override to scope CSRF enforcement to specific path prefixes.
     */
    public var enforceOn: (ApplicationCall) -> Boolean = { call ->
        when (call.request.local.method) {
            HttpMethod.Get, HttpMethod.Head, HttpMethod.Options -> false
            else -> true
        }
    }

    /** Cookie path. `/` so the same token covers the whole origin. */
    public var cookiePath: String = "/"

    /** Cookie max-age in seconds. 1 hour matches a typical session lifetime. */
    public var cookieMaxAgeSeconds: Int = DEFAULT_COOKIE_MAX_AGE_SECONDS

    public companion object {
        public const val DEFAULT_COOKIE_MAX_AGE_SECONDS: Int = 3_600
    }
}

/**
 * Ktor plugin implementing the [double-submit cookie](https://owasp.org/www-community/attacks/csrf)
 * defense.
 *
 * Flow:
 *  1. Client GETs a mint-eligible page (default: paths under `/screens/`). The plugin sets a cookie
 *     `csrf-token=<32-byte-base64url>` with `SameSite=Lax` and `httpOnly=false` so JS on the
 *     page can read it.
 *  2. Client copies the value into the `X-CSRF-Token` header on every mutating request.
 *  3. On `POST/PUT/PATCH/DELETE`, the plugin verifies the header matches the cookie and
 *     rejects mismatches with `403`.
 *
 * Why httpOnly=false: by RFC the double-submit pattern requires the JS app to read the cookie
 * and echo the value in a header. An attacker on a different origin cannot read the cookie
 * (same-origin policy) and cannot set the matching header (CORS preflight blocks it for
 * non-CORS-safelisted headers). httpOnly=true would prevent the legitimate JS client from
 * doing its job and break the protection.
 *
 * For server-rendered apps that don't need cross-origin XHR, prefer the synchronizer-token
 * pattern (server-bound token in a session table) — it's strictly stronger but requires a
 * session store the plugin doesn't currently take a SAM for.
 */
public val CsrfPlugin: RouteScopedPlugin<CsrfConfig> =
    createRouteScopedPlugin(name = "CsrfPlugin", createConfiguration = ::CsrfConfig) {
        val cfg = pluginConfig
        val cookieName = cfg.cookieName
        val headerName = cfg.headerName
        val mintOn = cfg.mintOn
        val enforceOn = cfg.enforceOn

        // Installed via [ShortCircuitPhase] rather than `onCall` so a 403 actually halts the
        // pipeline. `onCall` responds but does not stop the matched route handler from running,
        // which on a mutating endpoint would still fire its side effects on a request the client
        // sees rejected. See [ShortCircuitPhase].
        on(ShortCircuitPhase) { call ->
            // Mint pass — runs before routing. We attach a cookie to GETs that match the mint
            // predicate so the next mutating request has a token to echo.
            if (call.request.local.method == HttpMethod.Get && mintOn(call)) {
                val existing = call.request.cookies[cookieName]
                if (existing.isNullOrBlank()) {
                    call.response.cookies.append(
                        Cookie(
                            name = cookieName,
                            value = randomToken(),
                            path = cfg.cookiePath,
                            maxAge = cfg.cookieMaxAgeSeconds,
                            httpOnly = false,
                            secure = false,
                            extensions = mapOf("SameSite" to "Lax"),
                        ),
                    )
                }
            }

            // Enforce pass — runs before routing. Reject any mutating request whose header
            // does not match the cookie. Missing cookie or missing header → 403.
            if (enforceOn(call)) {
                val cookie = call.request.cookies[cookieName]
                val header = call.request.headers[headerName]
                if (cookie.isNullOrBlank() || header.isNullOrBlank() || cookie != header) {
                    call.respondText(
                        """{"error":"csrf token missing or invalid"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.Forbidden,
                    )
                    // Stop the pipeline: the guarded route handler must not run on a rejected
                    // request.
                    finish()
                }
            }
        }
    }

private val secureRandom = SecureRandom()
private const val TOKEN_BYTES: Int = 32

private fun randomToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
