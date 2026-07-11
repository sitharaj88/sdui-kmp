package dev.sdui.kmp.studio.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.state.AuthState
import kotlinx.browser.document

/**
 * Browser entrypoint for the sdui-kmp Studio admin console.
 *
 * Mirrors the bootstrap flow of [`:samples:sample-wasm`'s `Main.kt`](../../../sample-wasm)
 * — find `<div id="root">`, mount a `ComposeViewport`, wrap the tree in `MaterialTheme` —
 * but routes into the Studio's [App] root composable instead of a sample feed.
 *
 * Persistent session: before the first composition we re-hydrate [AuthState] from
 * `localStorage`; if a token is present we verify it with one cheap authenticated request
 * (`GET /admin/screens`). On 401 / 403 we clear the cache and fall back to the login screen.
 * This keeps editors signed in across refreshes without trusting a stale token blindly.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun main() {
    val root = document.getElementById("root")
        ?: error("missing <div id=\"root\"> in index.html — did the bundle load before the DOM?")
    ComposeViewport(root) {
        // Single shared instances for the lifetime of the page. The Studio is a long-lived SPA;
        // re-creating the API client on every recomposition would churn HTTP connections and
        // the in-memory bearer-token cache the way `installBearerAuth` reads it.
        val authState = remember { AuthState().also { it.restoreFromStorage() } }
        val api = remember { StudioApi(baseUrl = DEFAULT_STUDIO_BASE_URL, authState = authState) }
        var bootValidated by remember { mutableStateOf(authState.token == null) }

        // Validate any cached token by issuing a single authenticated request. We use
        // listScreens because every authenticated editor — regardless of role — can call it,
        // and it's the same request the Screens tab will issue immediately after.
        LaunchedEffect(Unit) {
            if (authState.token == null) {
                bootValidated = true
                return@LaunchedEffect
            }
            val ok = runCatching { api.listScreens() }.isSuccess
            if (!ok) authState.signOut()
            bootValidated = true
        }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                if (!bootValidated) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    App(authState = authState, api = api)
                }
            }
        }
    }
}

/**
 * Default base URL for the Studio admin REST API in local development.
 *
 * The parallel agent's `:studio-server` listens on port 8081 (port 8080 is the end-user
 * sample-server). This is intentionally NOT read from a `<meta>` tag or query string in the
 * skeleton phase — when we ship a hosted Studio in S5+, the server will inline the correct
 * base URL into `index.html` at request time.
 */
private const val DEFAULT_STUDIO_BASE_URL: String = "http://localhost:8081"
