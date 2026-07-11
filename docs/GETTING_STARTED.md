# Getting started

A 30-minute tour from zero to a working server-driven UI.

## What you need

- JDK 17 (Temurin or Corretto). `java -version` should report 17.
- A Compose Multiplatform target you care about: macOS for iOS, any OS for Desktop and Web,
  an Android emulator/device for Android.
- Optional: full Xcode (not just Command Line Tools) if you plan to run iOS samples or
  rebuild the iOS test binaries.

## 1. Run the samples (5 minutes)

Clone the repo and run the server first:

```bash
git clone <this-repo>
cd sdui-kmp
./gradlew :samples:sample-server:run
```

The server now listens on `http://localhost:8080`. Confirm with:

```bash
curl http://localhost:8080/health
# {"status":"ok"}

curl http://localhost:8080/screens/home
# Pretty-printed Screen JSON
```

In a second terminal, launch the Desktop client:

```bash
./gradlew :samples:sample-desktop:run
```

A window opens showing **Welcome to sdui-kmp** with three buttons:
- **Go to About** — push navigation across two screens
- **Log in** — a server-driven login form with client-side validation, optimistic submit, and inline errors
- **Feed demo** — a `LazyList` of items, each row's "Liked" toggle scoped to its own row
- **Tracking demo** — a `NativeSurface` (renders a fallback text on Desktop because no map factory is registered) plus an `AsyncImage` and template-bound ETA text

Try the Wasm sample too:

```bash
./gradlew :samples:sample-wasm:wasmJsBrowserDevelopmentRun
```

## 2. Author a new screen on the server (10 minutes)

Open [`samples/sample-server/src/main/kotlin/dev/sdui/kmp/sample/server/Main.kt`](../samples/sample-server/src/main/kotlin/dev/sdui/kmp/sample/server/Main.kt). Add a new route alongside the existing ones:

```kotlin
get("/screens/dashboard") { call.respond(dashboardScreen()) }
```

Then add the `dashboardScreen()` function:

```kotlin
internal fun dashboardScreen(): Screen = screen(id = "dashboard") {
    state(StateDeclaration(
        path = StatePath("user.name"),
        scope = StateScope.Screen,
        initial = JsonPrimitive("Stranger"),
    ))
    column(spacing = Spacing.Md, padding = EdgeInsets.all(Spacing.Lg)) {
        text(template("Welcome, {name}", "name" to "user.name"), style = TextStyleToken.Heading)
        text("This screen was emitted by the server in fewer than 10 lines of Kotlin.")
        button(
            label = "Back",
            action = Action.Navigate(Destination.Back()),
        )
    }
}
```

Restart the server (`Ctrl-C` then re-run). Open the Desktop sample, navigate to `/dashboard`,
or visit it via curl:

```bash
curl http://localhost:8080/screens/dashboard
```

The five rules to remember:
1. **One protocol, both sides.** The `Screen`, `Column`, `Text`, `Button`, `StatePath`,
   `Action`, etc. you used in the DSL are exactly the types the client decodes. No IDL.
2. **Additive-only evolution.** Add fields, never remove. The schema linter
   (`./gradlew verifyProtocolSnapshot`) blocks PRs that violate this.
3. **Client never crashes on unknown nodes.** Older clients render `fallback` (or nothing)
   when they meet a node type they don't know.
4. **Actions are data, not code.** `Action.Submit(...)` carries its own retry and optimistic
   policy. Replay, queue, or instrument actions uniformly.
5. **Semantic tokens only.** `Spacing.Md`, `ColorToken.Primary`, `TextStyleToken.Heading` —
   no hex colors or pixel sizes ever cross the wire.

## 3. Build a minimal client app from scratch (15 minutes)

Create a new Compose Desktop app in your own project. Add to `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation("dev.sdui.kmp:runtime:VERSION")
    implementation("dev.sdui.kmp:widgets-core:VERSION")
    implementation("dev.sdui.kmp:widgets-forms:VERSION")
    implementation("dev.sdui.kmp:widgets-media:VERSION")
    implementation("dev.sdui.kmp:transport-http:VERSION")
    implementation("io.ktor:ktor-client-java:3.0.2")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}
```

> **Note:** until 1.0 is published to a public repo, depend on the modules via `includeBuild`
> or local Maven publication. See [Distribution](#distribution) below.

Then a single `Main.kt`:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.sdui.kmp.runtime.*
import dev.sdui.kmp.transport.http.*
import dev.sdui.kmp.widgetscore.WidgetsCore
import dev.sdui.kmp.widgetsforms.WidgetsForms
import dev.sdui.kmp.widgetsmedia.WidgetsMedia
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "My SDUI app") {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                val client = remember { HttpClient(Java) { installSduiJson() } }
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
                val source = remember(client, route) {
                    HttpScreenSource(client, "http://localhost:8080", "screens${route ?: "/home"}")
                }
                DisposableEffect(source) { onDispose { source.close() } }
                SduiHost(
                    source = source,
                    registry = registry,
                    navigator = navigator,
                    submitHandler = remember(client) { KtorSubmitHandler(client, "http://localhost:8080") },
                )
            }
        }
    }
}
```

That's the entire client. ~50 lines for: HTTP transport with ETag caching, scoped state,
optimistic submits with rollback, retry-with-backoff, navigation, four widget modules.

## 4. What's next

- **[EXTENSION_GUIDE.md](EXTENSION_GUIDE.md)** — add custom widgets, native surfaces, telemetry, image loaders
- **[ARCHITECTURE.md](../ARCHITECTURE.md)** — full technical reference for every protocol type, runtime contract, and transport
- **[adr/](adr/)** — architecture decision records for the non-obvious choices
- **API reference** — generate via `./gradlew dokkaHtmlMultiModule`, then open `build/dokka/htmlMultiModule/index.html`

## Distribution

The artifacts are not yet published. Until 1.0:

- Use `includeBuild("path/to/sdui-kmp")` in your consuming project's `settings.gradle.kts`
- Or publish locally via `./gradlew publishToMavenLocal` (Maven publication plugin not yet configured)

Track the 1.0 release at [ROADMAP.md](../ROADMAP.md).
