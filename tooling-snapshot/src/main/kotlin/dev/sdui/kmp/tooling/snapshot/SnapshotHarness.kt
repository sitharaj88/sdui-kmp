package dev.sdui.kmp.tooling.snapshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.runtime.ActionDispatcher
import dev.sdui.kmp.runtime.LocalActionDispatcher
import dev.sdui.kmp.runtime.LocalRegistry
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.LocalTelemetry
import dev.sdui.kmp.runtime.NoopTelemetry
import dev.sdui.kmp.runtime.StateStore
import dev.sdui.kmp.runtime.WidgetRegistry
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

// ---------------------------------------------------------------------------------------------
//  Hand-rolled snapshot harness — JVM only.
//
//  Why hand-rolled and not Roborazzi?
//  --------------------------------------------------------------------------------------------
//  Roborazzi ships a `roborazzi-compose-desktop` artifact, but at the time this module landed
//  its Gradle plugin and runtime had not yet cut a release that targets Compose Multiplatform
//  1.7.3 + Kotlin 2.1.0. Rather than pin to an unreleased artifact (or vendor it), we drive
//  Compose's own `ImageComposeScene` directly: ~30 lines of code, no extra Maven dep, and
//  swapping it out for Roborazzi later is a one-class change because the test surface
//  (`snapshot(name) { ... }`) doesn't expose the harness internals.
//
//  Determinism guardrails
//  --------------------------------------------------------------------------------------------
//  * Fixed density (1f) so dp -> px is identity. No host DPI bleed-through.
//  * Fixed canvas dimensions (400 x 600 dp) — every test renders into the same frame so
//    layout differences become pixel diffs.
//  * Locale, country, encoding, and timezone are pinned in `build.gradle.kts` Test tasks.
//  * Compose's text shaping is deterministic given identical inputs; font selection on the
//    JVM falls through to Skiko's bundled fallback, so we don't depend on system fonts.
// ---------------------------------------------------------------------------------------------

private const val SNAPSHOT_WIDTH_DP = 400
private const val SNAPSHOT_HEIGHT_DP = 600

private const val PROP_RECORD = "roborazzi.test.record"

/**
 * Test-time scaffold that wires the four runtime composition locals every widget renderer
 * expects, then wraps the supplied [content] in `MaterialTheme + Surface` so semantic tokens
 * (`ColorToken.Surface`, `TextStyleToken.Heading`, etc.) resolve against the default M3 palette.
 *
 * Tests pass [registry] containing only the widgets they care about. The default `NoopTelemetry`
 * + empty `StateStore` + no-op dispatcher mean the suite never makes a network call.
 */
@Composable
@Suppress("FunctionNaming") // PascalCase by Compose convention.
internal fun SnapshotScaffold(
    registry: WidgetRegistry,
    content: @Composable () -> Unit,
) {
    val store = StateStore.Empty
    val dispatcher = NoopActionDispatcher
    CompositionLocalProvider(
        LocalRegistry provides registry,
        LocalStateStore provides store,
        LocalActionDispatcher provides dispatcher,
        LocalTelemetry provides NoopTelemetry,
    ) {
        MaterialTheme {
            Surface {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * Snapshot entry point used by every test method. Renders [content] off-screen with
 * `ImageComposeScene` at a fixed canvas, encodes the result to PNG, and either records the
 * PNG (when `-Proborazzi.test.record=true`) or compares it byte-for-byte against the baseline
 * stored at `src/test/resources/snapshots/<name>.png`.
 *
 * On verify-mode mismatches the diff bytes are written to
 * `build/snapshot-diffs/<name>.actual.png` so a developer can eyeball the regression.
 */
internal fun snapshot(name: String, content: @Composable () -> Unit) {
    require(name.isNotBlank()) { "snapshot name must be non-blank" }
    require(name.matches(Regex("[a-zA-Z0-9_-]+"))) {
        "snapshot name must be filesystem-safe (alphanumerics, underscores, dashes); got '$name'"
    }
    val actualBytes = renderToPng(content)
    val baselineFile = baselineFileFor(name)
    if (isRecordMode()) {
        baselineFile.parentFile.mkdirs()
        baselineFile.writeBytes(actualBytes)
        return
    }
    if (!baselineFile.exists()) {
        // Missing baseline is a hard fail in verify mode — the developer must run record
        // explicitly. This prevents silently committing an empty-test win.
        writeDiffArtifact(name, actualBytes)
        throw AssertionError(
            """
            Missing snapshot baseline: ${baselineFile.absolutePath}
            Run `./gradlew :tooling-snapshot:recordGoldenSnapshots` to regenerate, then commit the new PNG.
            Actual bytes captured at: ${diffFileFor(name).absolutePath}
            """.trimIndent(),
        )
    }
    val expectedBytes = baselineFile.readBytes()
    if (!expectedBytes.contentEquals(actualBytes)) {
        writeDiffArtifact(name, actualBytes)
        throw AssertionError(
            """
            Snapshot mismatch for '$name'.
              Baseline: ${baselineFile.absolutePath} (${expectedBytes.size} bytes)
              Actual:   ${diffFileFor(name).absolutePath} (${actualBytes.size} bytes)
            If the change is intentional, run `./gradlew :tooling-snapshot:recordGoldenSnapshots`.
            """.trimIndent(),
        )
    }
}

private fun renderToPng(content: @Composable () -> Unit): ByteArray {
    // Density 1f keeps dp -> px identity, so a 400x600 dp canvas yields a 400x600 px image
    // regardless of the JVM's screen density on the developer's machine.
    val density = Density(density = 1f, fontScale = 1f)
    val widthPx = SNAPSHOT_WIDTH_DP
    val heightPx = SNAPSHOT_HEIGHT_DP
    val scene = ImageComposeScene(
        width = widthPx,
        height = heightPx,
        density = density,
        content = { content() },
    )
    return try {
        val skiaImage = scene.render()
        val data = skiaImage.encodeToData(EncodedImageFormat.PNG)
            ?: error("Skia returned no PNG data for snapshot")
        data.bytes
    } finally {
        scene.close()
    }
}

private fun isRecordMode(): Boolean =
    System.getProperty(PROP_RECORD)?.toBoolean() == true

private fun baselineFileFor(name: String): File {
    // src/test/resources is on the test classpath; we resolve back to the source tree so the
    // record task writes into a checked-in location (build outputs would be transient).
    val moduleDir = File(System.getProperty("user.dir")).let {
        // When the Gradle test task runs in the module dir, that's our root. When the suite
        // is invoked from the repo root (e.g. via :tooling-snapshot:test) Gradle still sets
        // working dir to the module, so this branch is just a safety net.
        if (it.resolve("src/test/resources").exists()) it else it.resolve("tooling-snapshot")
    }
    return moduleDir.resolve("src/test/resources/snapshots/$name.png")
}

private fun diffFileFor(name: String): File =
    File(System.getProperty("user.dir")).resolve("build/snapshot-diffs/$name.actual.png")

private fun writeDiffArtifact(name: String, bytes: ByteArray) {
    val out = diffFileFor(name)
    out.parentFile.mkdirs()
    out.writeBytes(bytes)
}

/**
 * Action dispatcher used in snapshot tests. Buttons rendered into the canvas never actually
 * fire (the harness doesn't synthesise pointer input), but the runtime still requires a
 * non-null dispatcher in the composition local. Swallowing actions silently keeps the harness
 * resilient if someone adds a side-effect-on-compose path later.
 */
internal object NoopActionDispatcher : ActionDispatcher {
    override suspend fun dispatch(action: Action) { /* no-op */ }
}
