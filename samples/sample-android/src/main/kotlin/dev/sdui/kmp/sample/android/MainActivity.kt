package dev.sdui.kmp.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.InstallAndroidBackHandler
import dev.sdui.kmp.runtime.LocalNativeSurfaceRegistry
import dev.sdui.kmp.runtime.NativeSurfaceRegistry
import dev.sdui.kmp.runtime.NativeSurfaces
import dev.sdui.kmp.runtime.ScreenState
import dev.sdui.kmp.runtime.SduiHost
import dev.sdui.kmp.runtime.WidgetRegistry
import dev.sdui.kmp.runtime.rememberNavigator
import dev.sdui.kmp.transport.http.HttpScreenSource
import dev.sdui.kmp.transport.http.KtorSubmitHandler
import dev.sdui.kmp.transport.http.clearBearerToken
import dev.sdui.kmp.transport.http.installBearerAuth
import dev.sdui.kmp.transport.http.installSduiJson
import dev.sdui.kmp.widgetscore.WidgetsCore
import dev.sdui.kmp.widgetsforms.WidgetsForms
import dev.sdui.kmp.widgetsmedia.WidgetsMedia
import dev.sdui.kmp.widgetsnativemap.MapSurfaceFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Base URL resolution: the emulator maps host localhost to 10.0.2.2 (the default). For a
        // physical device over USB, run `adb reverse tcp:8080 tcp:8080` and launch with
        // `--es baseUrl http://127.0.0.1:8080`; for a LAN device pass the host's IP. Overridable
        // so the same build runs against either target without an edit.
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL) ?: DEFAULT_BASE_URL
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { AndroidApp(baseUrl) }
            }
        }
    }

    private companion object {
        const val EXTRA_BASE_URL = "baseUrl"
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"
    }
}

@Composable
private fun AndroidApp(baseUrl: String) {
    val client = remember {
        HttpClient(OkHttp) {
            installSduiJson()
            // Attach the demo JWT (once signed in) to every request; the sample-server's
            // /screens/* routes are Bearer-protected.
            installBearerAuth { SampleTokenStore.token }
            // Cookie store so the csrf-token cookie minted by /auth/csrf is replayed on the
            // /auth/login POST (double-submit CSRF check).
            install(HttpCookies)
        }
    }
    DisposableEffect(client) { onDispose { client.close() } }

    // Sign in with the demo credentials on first composition, then bump [refreshTick] so the
    // HttpScreenSource is rebuilt and re-fetches with the bearer token attached.
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(client, baseUrl) {
        if (SampleTokenStore.token == null && signInDemo(client, baseUrl)) {
            // Ktor caches the (initially null) result of loadTokens; drop it so the next request
            // reloads the now-present token instead of staying unauthenticated.
            client.clearBearerToken()
            refreshTick += 1
        }
    }

    val registry = remember {
        WidgetRegistry.build {
            WidgetsCore.register(this)
            WidgetsForms.register(this)
            WidgetsMedia.register(this)
            NativeSurfaces.register(this)
        }
    }
    // Kind-keyed registry of native surface factories. The MapSurfaceFactory falls back to
    // a placeholder Card when the manifest still carries the placeholder API key, so it is
    // safe to register unconditionally — the sample does not ship a real Google Maps key.
    val nativeRegistry = remember {
        NativeSurfaceRegistry.build(clientVersion = SchemaVersion.V1) {
            register(MapSurfaceFactory.instance(requireApiKey = true))
        }
    }
    val navigator = rememberNavigator(initial = "/home")
    InstallAndroidBackHandler(navigator)
    val route by navigator.current
    val submitHandler = remember(client) { KtorSubmitHandler(client, baseUrl) }

    val path = "screens${route ?: "/home"}"
    val source = remember(client, path, refreshTick) {
        HttpScreenSource(client = client, baseUrl = baseUrl, path = path)
    }
    DisposableEffect(source) { onDispose { source.close() } }

    val state by source.screen.collectAsState(initial = ScreenState.Loading)
    CompositionLocalProvider(LocalNativeSurfaceRegistry provides nativeRegistry) {
        when (val s = state) {
            ScreenState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…")
            }
            is ScreenState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Error loading $path from $baseUrl\n\n${s.error.message}")
            }
            is ScreenState.Ready -> SduiHost(
                screen = s.screen,
                registry = registry,
                navigator = navigator,
                submitHandler = submitHandler,
            )
        }
    }
}

/**
 * Process-global bearer-token holder for the demo sign-in. A production host would back the
 * token seam ([installBearerAuth]) with real, securely-stored credentials.
 */
private object SampleTokenStore {
    @Volatile
    var token: String? = null
}

private const val CSRF_COOKIE = "csrf-token"
private const val CSRF_HEADER = "X-CSRF-Token"

/**
 * Primes the CSRF double-submit cookie via `GET /auth/csrf` and returns its value to echo into
 * the [CSRF_HEADER] on the next mutating request. Null on transport failure.
 */
private suspend fun primeCsrf(client: HttpClient, baseUrl: String): String? = runCatching {
    client.get("${baseUrl.trimEnd('/')}/auth/csrf")
    client.cookies("${baseUrl.trimEnd('/')}/").firstOrNull { it.name == CSRF_COOKIE }?.value
}.getOrNull()

/**
 * Signs in with the demo credentials and stashes the resulting JWT in [SampleTokenStore].
 * **Sample-only** — production apps must not embed credentials in the binary.
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
    if (response.status.value !in 200..299) return@runCatching false
    val token = response.body<JsonObject>()["token"]?.jsonPrimitive?.content ?: return@runCatching false
    SampleTokenStore.token = token
    true
}.getOrDefault(false)
