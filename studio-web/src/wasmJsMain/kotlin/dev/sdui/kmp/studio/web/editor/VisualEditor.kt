package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Screen

/**
 * Top-level visual editor surface used by the Editor tab in `ScreenDetailView`.
 *
 * The component owns:
 *  * a [TreeMutator] keyed by [screen]'s id, seeded from the screen's root,
 *  * the current selection path (or `null`),
 *  * an "Apply to JSON" button that re-encodes the mutator's tree into [onScreenChange].
 *
 * Per the task brief we DO NOT auto-encode the tree on every mutation — that surprised the
 * previous agent's feedback loop. Operators see local changes immediately on the canvas, and
 * push them back to the JSON pane explicitly.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod")
public fun VisualEditor(
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mutator = remember(screen.id) { TreeMutator(initial = screen.root) }
    LaunchedEffect(screen.root) {
        // If the JSON pane edits the tree out from under us we re-seed without losing the
        // selection. Cheap because reset is O(1) — it just resets the history stack.
        if (mutator.current !== screen.root) {
            mutator.reset(screen.root)
        }
    }
    var selected by remember(screen.id) { mutableStateOf<TreePath?>(null) }
    val current = mutator.current
    val selectedNode = selected?.let { current.childAt(it) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Visual editor",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            OutlinedButton(onClick = { mutator.undo() }, enabled = mutator.canUndo) {
                Text("Undo")
            }
            OutlinedButton(onClick = { mutator.redo() }, enabled = mutator.canRedo) {
                Text("Redo")
            }
            Button(
                onClick = { onScreenChange(screen.copy(root = mutator.current)) },
            ) { Text("Apply to JSON") }
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WidgetPalette(
                onAdd = { node ->
                    val target = selected ?: emptyList()
                    val updated = mutator.current.insertingChild(target, node)
                    if (updated != null) mutator.replace(updated)
                },
                modifier = Modifier.weight(WEIGHT_PALETTE).fillMaxHeight(),
            )
            EditorCanvas(
                root = mutator.current,
                selected = selected,
                onSelect = { selected = it },
                onAddChild = { path ->
                    val updated = mutator.current.insertingChild(path, newText())
                    if (updated != null) mutator.replace(updated)
                },
                onRemove = { path ->
                    val updated = mutator.current.removingAt(path)
                    if (updated != null) {
                        mutator.replace(updated)
                        if (selected == path) selected = null
                    }
                },
                modifier = Modifier.weight(WEIGHT_CANVAS).fillMaxHeight(),
            )
            PropertyInspector(
                selected = selectedNode,
                onChange = { replacement ->
                    val path = selected ?: return@PropertyInspector
                    val updated = mutator.current.replacingAt(path, replacement)
                    if (updated != null) mutator.replace(updated)
                },
                modifier = Modifier.weight(WEIGHT_INSPECTOR).fillMaxHeight(),
            )
        }
    }
}

private const val WEIGHT_PALETTE: Float = 1f
private const val WEIGHT_CANVAS: Float = 2f
private const val WEIGHT_INSPECTOR: Float = 1.5f
