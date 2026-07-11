package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import kotlin.random.Random
import dev.sdui.kmp.protocol.Column as ColumnNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Side panel listing the widget kinds an operator can spawn into the canvas.
 *
 * Each entry is a button; clicking calls [onAdd] with a freshly-built [UiNode] of that type. The
 * caller decides whether to insert at root or under the selected container.
 *
 * Per ADR-0019 a token-only inspector means we cannot expose hex colours or pixel sizes here
 * either — every spawned node uses default token values.
 */
@Composable
@Suppress("FunctionNaming")
public fun WidgetPalette(
    onAdd: (UiNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Add widget",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            OutlinedButton(
                onClick = { onAdd(newText()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Text") }
            OutlinedButton(
                onClick = { onAdd(newButton()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Button") }
            OutlinedButton(
                onClick = { onAdd(newColumn()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Column") }
        }
    }
}

/** Builds a fresh [TextNode] with placeholder content and a unique node id. */
internal fun newText(): TextNode = TextNode(
    id = freshNodeId("text"),
    since = SchemaVersion.V1,
    content = Value.ofString("New text"),
)

/** Builds a fresh [Button] with a no-op navigation action and a unique node id. */
internal fun newButton(): Button = Button(
    id = freshNodeId("button"),
    since = SchemaVersion.V1,
    label = Value.ofString("Button"),
    action = Action.Navigate(destination = Destination.ScreenDest(route = "/")),
)

/** Builds a fresh empty [ColumnNode] with a unique node id. */
internal fun newColumn(): ColumnNode = ColumnNode(
    id = freshNodeId("column"),
    since = SchemaVersion.V1,
    children = emptyList(),
)

/**
 * Generates a node id that is unlikely to collide within a single editor session. The Studio
 * server validates uniqueness on save; collisions surface as decode-time validation errors.
 */
internal fun freshNodeId(prefix: String): NodeId =
    NodeId("$prefix-${Random.nextInt(0, Int.MAX_VALUE).toString(NODE_ID_RADIX)}")

private const val NODE_ID_RADIX: Int = 36
