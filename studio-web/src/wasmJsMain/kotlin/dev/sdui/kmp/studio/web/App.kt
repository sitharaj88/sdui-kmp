package dev.sdui.kmp.studio.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.state.AuthState

/**
 * Top-level Studio composable.
 *
 * Owns the auth-driven branch:
 *  * No token in [authState] → render the [LoginScreen]; on success the screen pushes a
 *    token + role into [authState] and we recompose into the main shell.
 *  * Token present → render the [MainShell] with its tabbed navigation.
 *
 * Sign-out is implemented by [MainShell] clearing [authState], which trips the same branch
 * back to the login screen. Token storage is in-memory only by design (skeleton phase) —
 * a hard refresh signs the operator out. Persistent sessions land with S5.
 *
 * Routing is intentionally trivial here. The Studio has very few top-level destinations
 * (Screens, Audit) and the URL bar is not yet a source of truth; deep links and bookmarkable
 * detail views are a follow-up once Compose Navigation for Wasm stabilises.
 */
@Composable
public fun App(authState: AuthState, api: StudioApi) {
    val token by authState.tokenState
    if (token == null) {
        LoginScreen(api = api, authState = authState)
    } else {
        MainShell(api = api, authState = authState)
    }
}
