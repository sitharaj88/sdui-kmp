package dev.sdui.kmp.sample.wasm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.sdui.kmp.runtime.ScreenState
import dev.sdui.kmp.runtime.NativeSurfaces
import dev.sdui.kmp.runtime.SduiHost
import dev.sdui.kmp.runtime.WidgetRegistry
import dev.sdui.kmp.runtime.rememberNavigator
import dev.sdui.kmp.transport.http.HttpScreenSource
import dev.sdui.kmp.transport.http.KtorSubmitHandler
import dev.sdui.kmp.transport.http.installSduiJson
import dev.sdui.kmp.widgetscore.WidgetsCore
import dev.sdui.kmp.widgetsforms.WidgetsForms
import dev.sdui.kmp.widgetsmedia.WidgetsMedia
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
public fun main() {
    val root = document.getElementById("root") ?: error("missing <div id=root> in index.html")
    ComposeViewport(root) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) { WasmApp() }
        }
    }
}

@Composable
private fun WasmApp() {
    // Default to relative paths so the bundled page can be hosted next to the server.
    val baseUrl = "http://localhost:8080"
    val client = remember { HttpClient(Js) { installSduiJson() } }
    DisposableEffect(client) { onDispose { client.close() } }

    val registry = remember {
        WidgetRegistry.build {
            WidgetsCore.register(this)
            WidgetsForms.register(this)
            WidgetsMedia.register(this)
            NativeSurfaces.register(this)
        }
    }
    val navigator = rememberNavigator(initial = "/home")
    val route by navigator.current
    val submitHandler = remember(client) { KtorSubmitHandler(client, baseUrl) }

    val path = "screens${route ?: "/home"}"
    val source = remember(client, path) {
        HttpScreenSource(client = client, baseUrl = baseUrl, path = path)
    }
    DisposableEffect(source) { onDispose { source.close() } }

    val state by source.screen.collectAsState(initial = ScreenState.Loading)
    when (val s = state) {
        ScreenState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        is ScreenState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Error loading $path: ${s.error.message}")
        }
        is ScreenState.Ready -> SduiHost(
            screen = s.screen,
            registry = registry,
            navigator = navigator,
            submitHandler = submitHandler,
        )
    }
}
