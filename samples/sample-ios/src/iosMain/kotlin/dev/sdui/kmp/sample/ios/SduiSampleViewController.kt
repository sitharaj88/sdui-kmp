package dev.sdui.kmp.sample.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import coil3.PlatformContext
import dev.sdui.kmp.runtime.LocalNestedScreenSourceFactory
import dev.sdui.kmp.runtime.NativeSurfaces
import dev.sdui.kmp.runtime.NestedScreenSourceFactory
import dev.sdui.kmp.runtime.ScreenState
import dev.sdui.kmp.runtime.SduiHost
import dev.sdui.kmp.runtime.WidgetRegistry
import dev.sdui.kmp.runtime.rememberNavigator
import dev.sdui.kmp.transport.http.HttpScreenSource
import dev.sdui.kmp.transport.http.KtorSubmitHandler
import dev.sdui.kmp.transport.http.installBearerAuth
import dev.sdui.kmp.transport.http.installSduiJson
import dev.sdui.kmp.transport.live.WebSocketLiveSource
import dev.sdui.kmp.transport.live.installWebSockets
import dev.sdui.kmp.widgetscore.WidgetsCore
import dev.sdui.kmp.widgetsforms.WidgetsForms
import dev.sdui.kmp.widgetsmedia.LocalImageLoader
import dev.sdui.kmp.widgetsmedia.WidgetsMedia
import dev.sdui.kmp.widgetsmediacoil.Coil3ImageLoader
import dev.sdui.kmp.widgetsnav.WidgetsNav
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.UIKit.UIViewController

/**
 * Process-global bearer token store for the iOS sample.
 *
 * Production iOS hosts would back this with the Keychain (and observe changes via a
 * `MutableStateFlow<String?>` so the UI can react to sign-in state changes from any
 * surface in the app); the demo just uses a plain `var`. iOS is single-process and
 * Compose runs on the main thread, so the token field is always read/written on the
 * same thread — no `@Volatile` needed (and `kotlin.jvm.Volatile` isn't available on
 * Kotlin/Native anyway).
 */
internal object SampleTokenStore {
    var token: String? = null
}

/** Hook handed to [installBearerAuth]. Host decides where the token lives; demo: in-process. */
private fun loadStoredToken(): String? = SampleTokenStore.token

/**
 * Default base URL for the sample server.
 *
 * The iOS Simulator shares its network namespace with the host Mac, so `localhost:8080`
 * resolves to `:samples:sample-server` running on the developer machine. Real iOS
 * devices need either a LAN IP or a tunnelled URL — pass [baseUrl] explicitly to
 * [SduiSampleViewController] in that case.
 */
public const val DEFAULT_BASE_URL: String = "http://localhost:8080"

/**
 * Entry point consumed by Swift via the generated Objective-C interop header
 * (`SduiSampleSharedSduiSampleViewControllerKt.SduiSampleViewController(...)`).
 *
 * Returns a [UIViewController] hosting the same Compose Multiplatform UI as
 * `:samples:sample-desktop` — `SduiHost` driving an `HttpScreenSource` backed by a
 * Ktor `Darwin` client, with the Coil 3 image loader supplied via
 * [LocalImageLoader] and a Sign in / Sign out toolbar above the rendered screen.
 *
 * @param baseUrl Override the default `http://localhost:8080` — useful for running the
 *   sample server on a LAN host, behind ngrok, or in CI.
 */
@Suppress("FunctionName")
public fun SduiSampleViewController(baseUrl: String = DEFAULT_BASE_URL): UIViewController =
    ComposeUIViewController {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) { IosApp(baseUrl) }
        }
    }

@Composable
private fun IosApp(baseUrl: String) {
    val client = remember {
        HttpClient(Darwin) {
            installSduiJson()
            installBearerAuth { loadStoredToken() }
            // WebSocket plugin so the same client backs both HTTP screen fetches and the
            // WebSocketLiveSource subscription on `/live/screens/home`.
            installWebSockets()
            // Cookie store so the CSRF cookie minted by `/auth/csrf` is replayed on
            // subsequent POSTs to `/auth/*` and we can read it back into the
            // X-CSRF-Token header.
            install(HttpCookies)
        }
    }
    DisposableEffect(client) { onDispose { client.close() } }

    // Coil 3 image loader — fetches AsyncImage URLs over Ktor 3 (Darwin engine on iOS).
    val imageLoader = remember { Coil3ImageLoader.create(PlatformContext.INSTANCE) }

    val registry = remember {
        WidgetRegistry.build {
            WidgetsCore.register(this)
            WidgetsForms.register(this)
            WidgetsMedia.register(this)
            WidgetsNav.register(this)
            NativeSurfaces.register(this)
        }
    }
    // Factory used by NavHost(kind = Tab/BottomSheet) to spin up a child SduiHost per
    // nested route. Mirrors the desktop sample exactly.
    val nestedScreenSourceFactory: NestedScreenSourceFactory = remember(client, baseUrl) {
        { route: String ->
            val path = "screens/${route.trimStart('/')}"
            HttpScreenSource(client = client, baseUrl = baseUrl, path = path)
        }
    }
    val navigator = rememberNavigator(initial = "/home")
    val route by navigator.current
    val submitHandler = remember(client) { KtorSubmitHandler(client, baseUrl) }

    // Bumping this counter forces the HttpScreenSource to be reconstructed after a
    // fresh sign-in, which is the simplest way to retry the initial fetch with the
    // new token.
    var refreshTick by remember { mutableIntStateOf(0) }
    var signInStatus by remember { mutableStateOf("Not signed in") }
    val coroutineScope = rememberCoroutineScope()

    val path = "screens${route ?: "/home"}"
    val source = remember(client, path, refreshTick) {
        HttpScreenSource(client = client, baseUrl = baseUrl, path = path)
    }
    DisposableEffect(source) { onDispose { source.close() } }

    // Hot-reload subscription for the home screen only. Same per-screen wiring caveat
    // as the desktop sample — see comment in :samples:sample-desktop's Main.kt.
    val liveBaseUrl = baseUrl.removePrefix("http://").removePrefix("https://").trimEnd('/')
    val liveScheme = if (baseUrl.startsWith("https")) "wss" else "ws"
    val liveSource = remember(client, liveBaseUrl, liveScheme, route) {
        if (route == "/home") {
            WebSocketLiveSource(
                client = client,
                url = "$liveScheme://$liveBaseUrl/live/screens/home",
            )
        } else {
            null
        }
    }
    DisposableEffect(liveSource) {
        onDispose { liveSource?.close() }
    }

    val state by source.screen.collectAsState(initial = ScreenState.Loading)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        signInStatus = "Signing in…"
                        val ok = signInDemo(client, baseUrl)
                        signInStatus = if (ok) "Signed in" else "Sign-in failed"
                        if (ok) refreshTick++
                    }
                },
            ) { Text("Sign in (sample)") }
            Button(
                onClick = {
                    coroutineScope.launch {
                        val token = SampleTokenStore.token
                        if (token == null) {
                            signInStatus = "Not signed in"
                            return@launch
                        }
                        signInStatus = "Signing out…"
                        val ok = signOutDemo(client, baseUrl, token)
                        SampleTokenStore.token = null
                        signInStatus = if (ok) "Signed out" else "Sign-out failed (token cleared)"
                        // Refetch — protected screens now return 401 so we surface the
                        // error path; in a real app you'd navigate to the public login
                        // screen.
                        refreshTick++
                    }
                },
            ) { Text("Sign out") }
            Spacer(Modifier.size(12.dp))
            Text(signInStatus, style = MaterialTheme.typography.bodySmall)
        }
        CompositionLocalProvider(
            LocalImageLoader provides imageLoader,
            LocalNestedScreenSourceFactory provides nestedScreenSourceFactory,
        ) {
            when (val s = state) {
                ScreenState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
                is ScreenState.Error -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Could not load $path from $baseUrl\n\n${s.error.message}")
                }
                is ScreenState.Ready -> SduiHost(
                    screen = s.screen,
                    registry = registry,
                    navigator = navigator,
                    submitHandler = submitHandler,
                    liveSource = liveSource,
                )
            }
        }
    }
}

private const val CSRF_COOKIE: String = "csrf-token"
private const val CSRF_HEADER: String = "X-CSRF-Token"

/**
 * Fetch (and cache via the client's cookie store) the CSRF cookie, returning the token
 * value to echo into the `X-CSRF-Token` header on the next mutating request. Returns
 * null on transport failure — caller falls back to a "not signed in" state.
 */
private suspend fun primeCsrf(client: HttpClient, baseUrl: String): String? = runCatching {
    client.get("${baseUrl.trimEnd('/')}/auth/csrf")
    val cookies = client.cookies("${baseUrl.trimEnd('/')}/")
    cookies.firstOrNull { it.name == CSRF_COOKIE }?.value
}.getOrNull()

/**
 * Calls `/auth/login` with the demo password and stashes the resulting JWT in
 * [SampleTokenStore]. Returns true on success. **Sample-only** — production apps
 * should not embed credentials in the binary.
 */
private suspend fun signInDemo(client: HttpClient, baseUrl: String): Boolean = runCatching {
    val csrf = primeCsrf(client, baseUrl) ?: return@runCatching false
    val response = client.post("${baseUrl.trimEnd('/')}/auth/login") {
        contentType(ContentType.Application.Json)
        header(CSRF_HEADER, csrf)
        setBody(
            buildJsonObject {
                put("email", JsonPrimitive("demo@sdui.dev"))
                put("password", JsonPrimitive("password"))
            },
        )
    }
    if (!response.status.value.let { it in 200..299 }) return@runCatching false
    val payload: JsonObject = response.body()
    val token = payload["token"]?.jsonPrimitive?.content
    if (token != null) {
        SampleTokenStore.token = token
        true
    } else {
        false
    }
}.getOrDefault(false)

/**
 * Calls `POST /auth/logout` with the supplied bearer token and the cached CSRF cookie.
 * The server revokes the session row keyed by the JWT's `jti` claim; the next protected
 * request with this token returns 401.
 */
private suspend fun signOutDemo(client: HttpClient, baseUrl: String, token: String): Boolean = runCatching {
    val csrf = primeCsrf(client, baseUrl) ?: return@runCatching false
    val response = client.post("${baseUrl.trimEnd('/')}/auth/logout") {
        bearerAuth(token)
        header(CSRF_HEADER, csrf)
    }
    response.status.value in 200..299
}.getOrDefault(false)
