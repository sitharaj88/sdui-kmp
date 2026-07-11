package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Container
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.protocol.Button as ButtonNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Click-to-select tree visualisation for the visual editor.
 *
 * The canvas walks the [TreeMutator]'s current root and renders one [Card] per node, indented by
 * depth. It does NOT use `SduiHost`: per ADR-0019 the editor must not fire actions or mutate
 * state — a hand-written renderer is simpler than threading mock dispatchers through the
 * production renderer.
 *
 * Selection is reported via [onSelect]. Containers expose an "Add child" button that delegates
 * back to the caller via [onAddChild]; this avoids the canvas knowing what kind of widget the
 * palette intends to spawn.
 */
@Composable
@Suppress("FunctionNaming")
public fun EditorCanvas(
    root: UiNode,
    selected: TreePath?,
    onSelect: (TreePath?) -> Unit,
    onAddChild: (TreePath) -> Unit,
    onRemove: (TreePath) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(text = "Canvas", style = MaterialTheme.typography.titleSmall)
                Box(modifier = Modifier.padding(start = 8.dp))
                if (selected != null) {
                    TextButton(onClick = { onSelect(null) }) { Text("Clear selection") }
                }
            }
            NodeRow(
                node = root,
                path = emptyList(),
                depth = 0,
                selected = selected,
                onSelect = onSelect,
                onAddChild = onAddChild,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun NodeRow(
    node: UiNode,
    path: TreePath,
    depth: Int,
    selected: TreePath?,
    onSelect: (TreePath?) -> Unit,
    onAddChild: (TreePath) -> Unit,
    onRemove: (TreePath) -> Unit,
) {
    val isSelected = selected == path
    val highlight = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = highlight),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * INDENT_DP).dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = describe(node),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Box(modifier = Modifier.padding(start = 8.dp))
                TextButton(onClick = { onSelect(path) }) {
                    Text(if (isSelected) "Selected" else "Select")
                }
                if (node is Container) {
                    OutlinedButton(
                        onClick = { onAddChild(path) },
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Text("Add child") }
                }
                if (path.isNotEmpty()) {
                    TextButton(
                        onClick = { onRemove(path) },
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Text("Remove") }
                }
            }
        }
    }
    if (node is Container) {
        node.children.forEachIndexed { index, child ->
            NodeRow(
                node = child,
                path = path + index,
                depth = depth + 1,
                selected = selected,
                onSelect = onSelect,
                onAddChild = onAddChild,
                onRemove = onRemove,
            )
        }
    }
}

/** Human-readable summary of [node] for the canvas row label. */
private fun describe(node: UiNode): String {
    val typeName = node::class.simpleName ?: "Node"
    val idValue = node.id.value
    val suffix = when (node) {
        is TextNode -> previewLiteralString(node.content)?.let { "  \"${it.take(SUMMARY_MAX)}\"" } ?: ""
        is ButtonNode -> previewLiteralString(node.label)?.let { "  [${it.take(SUMMARY_MAX)}]" } ?: ""
        else -> ""
    }
    return "$typeName  ($idValue)$suffix"
}

/**
 * Best-effort preview of a [Value.Literal]'s string contents. Returns `null` for binds, templates,
 * or non-string literals — the canvas just shows the type name in those cases.
 */
private fun previewLiteralString(value: Value<String>): String? {
    val literal = value as? Value.Literal<*> ?: return null
    val content = literal.value.toString()
    return content.trim('"')
}

private const val INDENT_DP: Int = 16
private const val SUMMARY_MAX: Int = 30
