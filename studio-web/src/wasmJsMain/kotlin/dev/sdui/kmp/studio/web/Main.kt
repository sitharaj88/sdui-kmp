package dev.sdui.kmp.studio.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.state.AuthState
import dev.sdui.kmp.studio.web.theme.StudioTheme
import kotlinx.browser.document

/**
 * Browser entrypoint for the sdui-kmp Studio admin console.
 *
 * Mirrors the bootstrap flow of [`:samples:sample-wasm`'s `Main.kt`](../../../sample-wasm)
 * — find `<div id="root">`, mount a `ComposeViewport`, wrap the tree in the dark
 * [StudioTheme] — but routes into the Studio's [App] root composable instead of a sample feed.
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

        StudioTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (!bootValidated) {
                    BootSplash()
                } else {
                    App(authState = authState, api = api)
                }
            }
        }
    }
}

/**
 * Branded in-Compose boot state shown while a cached token is being validated. Mirrors the
 * pre-wasm CSS splash in `index.html` so the handoff from DOM to canvas is seamless.
 */
@Composable
private fun BootSplash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BOOT_SPLASH_GAP),
        ) {
            StudioLogoMark(size = BOOT_LOGO_SIZE)
            CircularProgressIndicator(
                modifier = Modifier.size(BOOT_SPINNER_SIZE),
                strokeWidth = BOOT_SPINNER_STROKE,
            )
            Text(
                text = "Loading Studio…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The Studio's square accent logo mark — an "S" tile reused by the boot splash, login card,
 * and top bar so the brand reads consistently at every size.
 */
@Composable
internal fun StudioLogoMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "S",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val BOOT_SPLASH_GAP = 12.dp
private val BOOT_LOGO_SIZE = 28.dp
private val BOOT_SPINNER_SIZE = 28.dp
private val BOOT_SPINNER_STROKE = 3.dp

/**
 * Default base URL for the Studio admin REST API in local development.
 *
 * The parallel agent's `:studio-server` listens on port 8081 (port 8080 is the end-user
 * sample-server). This is intentionally NOT read from a `<meta>` tag or query string in the
 * skeleton phase — when we ship a hosted Studio in S5+, the server will inline the correct
 * base URL into `index.html` at request time.
 */
private const val DEFAULT_STUDIO_BASE_URL: String = "http://localhost:8081"
