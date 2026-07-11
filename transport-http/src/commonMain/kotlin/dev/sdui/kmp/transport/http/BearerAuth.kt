package dev.sdui.kmp.transport.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer

/**
 * Installs Ktor's `Auth` plugin in `bearer` mode so every outbound request carries an
 * `Authorization: Bearer <token>` header.
 *
 * Hosts own the storage decision: the [tokenProvider] is invoked on demand and may read
 * from DataStore, the iOS Keychain, the JS `window.localStorage`, or any other backing
 * store the host has set up. Returning `null` causes Ktor to omit the header — the request
 * proceeds unauthenticated.
 *
 * Ktor caches the value returned by `loadTokens`. To keep the header in sync with a token that
 * changes at runtime (e.g. a host that starts unauthenticated and signs in later), `refreshTokens`
 * is also wired to re-read [tokenProvider]: when a request gets a `401`, Ktor re-invokes the
 * provider and retries with whatever token is current, so a cached `null` (or a stale token) does
 * not permanently wedge every subsequent request as unauthenticated. Hosts can still force an
 * eager invalidation via [clearBearerToken].
 */
public fun HttpClientConfig<*>.installBearerAuth(tokenProvider: suspend () -> String?) {
    install(Auth) {
        bearer {
            loadTokens {
                tokenProvider()?.let { BearerTokens(accessToken = it, refreshToken = "") }
            }
            // Re-read the live token on a 401 challenge so a stale/absent cached value is replaced
            // with the current one and the request is retried. Returning the same token twice does
            // not loop — Ktor retries auth at most once per request.
            refreshTokens {
                tokenProvider()?.let { BearerTokens(accessToken = it, refreshToken = "") }
            }
        }
    }
}

/**
 * Invalidates the token Ktor cached from the last [installBearerAuth] `loadTokens` call, so the
 * next request re-invokes the provider.
 *
 * Needed when the token changes out of band — e.g. a host that starts unauthenticated (provider
 * returns `null`, which Ktor caches) and signs in later: without clearing, the cached `null`
 * keeps every subsequent request unauthenticated. No-op if `Auth`/`bearer` was not installed.
 */
public fun HttpClient.clearBearerToken() {
    authProvider<BearerAuthProvider>()?.clearToken()
}
