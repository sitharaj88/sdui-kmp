package dev.sdui.kmp.tooling.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.runtime.NativeSurfaces
import dev.sdui.kmp.runtime.SduiHost
import dev.sdui.kmp.runtime.WidgetRegistry
import dev.sdui.kmp.widgetscore.WidgetsCore
import dev.sdui.kmp.widgetsforms.WidgetsForms
import dev.sdui.kmp.widgetsmedia.WidgetsMedia
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Compose Desktop preview harness.
 *
 * Usage: `./gradlew :tooling-preview:run --args="/abs/path/to/my-screen.sdui.json"`
 *
 * The window opens with the raw JSON on the left and the rendered screen on the right.
 * Save the file in any editor and the right pane re-renders instantly.
 */
public fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: run {
        System.err.println("Usage: sdui-kmp-preview <path/to/screen.sdui.json>")
        exitProcess(2)
    }
    val file = Paths.get(path).toAbsolutePath()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "sdui-kmp preview — ${file.fileName}",
            state = rememberWindowState(width = 960.dp, height = 640.dp),
        ) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { PreviewApp(file) }
            }
        }
    }
}

@Composable
private fun PreviewApp(file: Path) {
    var raw by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf<Screen?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        watchFile(file).collect { contents ->
            raw = contents
            runCatching { SduiJson.decodeFromString(Screen.serializer(), contents) }
                .onSuccess { screen = it; error = null }
                .onFailure { error = it.message ?: it::class.simpleName.orEmpty() }
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

    Row(Modifier.fillMaxSize()) {
        // Left — JSON source
        Column(Modifier.fillMaxHeight().weight(1f).padding(12.dp)) {
            Text("JSON", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                text = raw,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        VerticalDivider()
        // Right — rendered Screen or error
        Box(Modifier.fillMaxHeight().weight(1f).padding(12.dp)) {
            val e = error
            val s = screen
            when {
                e != null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Parse error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(e, style = MaterialTheme.typography.bodyMedium)
                }
                s != null -> SduiHost(screen = s, registry = registry)
                else -> Text("Loading…")
            }
        }
    }
}
