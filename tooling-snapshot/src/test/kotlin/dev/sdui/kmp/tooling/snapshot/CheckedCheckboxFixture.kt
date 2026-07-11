package dev.sdui.kmp.tooling.snapshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.StateStore
import dev.sdui.kmp.widgetsforms.CheckboxRenderer
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders [CheckboxRenderer] with a pre-seeded `StateStore` so the bound `agree` path reads as
 * `true` and the renderer paints the "checked" Material3 visual. Lives in a dedicated file so
 * the test method stays a one-liner.
 */
@Composable
internal fun CheckedCheckboxFixture() {
    val seeded = StateStore.of(StatePath("agree") to JsonPrimitive(true))
    CompositionLocalProvider(LocalStateStore provides seeded) {
        CheckboxRenderer.Render(
            node = Checkbox(
                id = NodeId("cb-on"),
                since = SchemaVersion.V1,
                path = StatePath("agree"),
                label = Value.ofString("Accept terms"),
            ),
            modifier = Modifier,
        )
    }
}
