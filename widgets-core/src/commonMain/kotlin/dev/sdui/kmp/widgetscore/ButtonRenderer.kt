package dev.sdui.kmp.widgetscore

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Button as ButtonNode
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.LocalActionDispatcher
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.applyA11y
import kotlin.reflect.KClass
import kotlinx.coroutines.launch

/**
 * 48 dp x 48 dp is the [WCAG 2.2 SC 2.5.8 (Target Size — Minimum)](https://www.w3.org/TR/WCAG22/#target-size-minimum)
 * recommended interactive target size, matching Material's own touch-target floor. M3's
 * `Button` already enforces a 40 dp height; we lift it to 48 dp so all four [ButtonStyle]
 * variants — including [ButtonStyle.Tertiary] (which renders as a plain `TextButton`) —
 * always meet the spec without relying on the server to size them.
 */
private val MinTouchTargetSize = 48.dp

public object ButtonRenderer : NodeRenderer<ButtonNode> {
    override val nodeClass: KClass<ButtonNode> = ButtonNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: ButtonNode, modifier: Modifier) {
        val dispatcher = LocalActionDispatcher.current
        val store = LocalStateStore.current
        val scope = rememberCoroutineScope()
        val label = node.label.resolveString()
        val onClick: () -> Unit = { scope.launch { dispatcher.dispatch(node.action) } }
        // Order matters: enforce min size BEFORE applyA11y so the semantics modifier wraps
        // the full touch region, not just the inner content rectangle.
        val m = modifier
            .heightIn(min = MinTouchTargetSize)
            .widthIn(min = MinTouchTargetSize)
            .applyA11y(node.a11y, store)
        when (node.style) {
            ButtonStyle.Primary -> Button(onClick = onClick, modifier = m) { Text(label) }
            ButtonStyle.Secondary -> OutlinedButton(onClick = onClick, modifier = m) { Text(label) }
            ButtonStyle.Tertiary -> TextButton(onClick = onClick, modifier = m) { Text(label) }
            ButtonStyle.Destructive -> Button(
                onClick = onClick,
                modifier = m,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(label) }
        }
    }
}
