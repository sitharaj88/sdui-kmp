package dev.sdui.kmp.studio.web.state

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * In-memory + `localStorage`-persisted holder for the Studio operator's session.
 *
 * Stores the JWT [tokenState], operator [emailState] and [roleState] as `mutableStateOf`-backed
 * `MutableState` properties so any composable reading them via `by` delegation recomposes on
 * sign-in or sign-out.
 *
 * Persistence: every call to [signIn] / [signOut] mirrors the new value into
 * `window.localStorage` under [TOKEN_KEY] / [EMAIL_KEY] / [ROLE_KEY]. On bootstrap callers should
 * invoke [restoreFromStorage] which re-hydrates the in-memory fields from `localStorage` (if a
 * cached token exists). The bootstrap flow then validates the token by issuing one
 * authenticated request — see `Main.kt` — and clears the cache on 401.
 *
 * This is deliberately separate from anything in `:transport-http`'s `BearerAuth` — that helper
 * assumes a single end-user session per HttpClient. The Studio's operator JWT is a different
 * domain and is wired to its own `HttpClient(Js)` instance inside [`StudioApi`]
 * (../api/StudioApi.kt). Mixing them would let an admin token leak into an end-user preview
 * request, which we want to make structurally impossible.
 */
public class AuthState {
    /** Backing `MutableState<String?>` for the JWT — `null` means signed out. */
    public val tokenState: MutableState<String?> = mutableStateOf(null)

    /** Backing `MutableState<String?>` for the operator's role string. */
    public val roleState: MutableState<String?> = mutableStateOf(null)

    /** Backing `MutableState<String?>` for the operator's email — surfaced in the user menu. */
    public val emailState: MutableState<String?> = mutableStateOf(null)

    /** Convenience accessor — non-`@Composable` callers should snapshot via `tokenState.value`. */
    public val token: String?
        get() = tokenState.value

    /** Convenience accessor for the role string. */
    public val role: String?
        get() = roleState.value

    /** Convenience accessor for the cached email. */
    public val email: String?
        get() = emailState.value

    /**
     * Atomically set token, role, and email on a successful login and mirror them to
     * `localStorage`. The JWT is a short-lived bearer; reading it back from `localStorage`
     * means a hard refresh keeps the operator signed in until the token expires.
     */
    public fun signIn(token: String, role: String?, email: String?) {
        tokenState.value = token
        roleState.value = role
        emailState.value = email
        runCatching {
            localStorage[TOKEN_KEY] = token
            if (role != null) localStorage[ROLE_KEY] = role else localStorage.removeItem(ROLE_KEY)
            if (email != null) localStorage[EMAIL_KEY] = email else localStorage.removeItem(EMAIL_KEY)
        }
    }

    /**
     * Clear all session state and erase the cached token from `localStorage`. The [App]
     * composable will route back to the login screen on the next recomposition.
     */
    public fun signOut() {
        tokenState.value = null
        roleState.value = null
        emailState.value = null
        runCatching {
            localStorage.removeItem(TOKEN_KEY)
            localStorage.removeItem(ROLE_KEY)
            localStorage.removeItem(EMAIL_KEY)
        }
    }

    /**
     * Re-hydrate the in-memory state from `localStorage`. Called once at app boot before any
     * UI renders; if no cached token is present this is a no-op and the login screen renders
     * as usual. The caller is expected to validate the cached token by issuing one
     * authenticated request and calling [signOut] on 401.
     */
    public fun restoreFromStorage() {
        runCatching {
            val cachedToken = localStorage[TOKEN_KEY] ?: return@runCatching
            tokenState.value = cachedToken
            roleState.value = localStorage[ROLE_KEY]
            emailState.value = localStorage[EMAIL_KEY]
        }
    }

    public companion object {
        /** `localStorage` key under which the JWT is cached. */
        public const val TOKEN_KEY: String = "sdui-studio-token"

        /** `localStorage` key for the cached editor role. */
        public const val ROLE_KEY: String = "sdui-studio-role"

        /** `localStorage` key for the cached editor email. */
        public const val EMAIL_KEY: String = "sdui-studio-email"
    }
}
