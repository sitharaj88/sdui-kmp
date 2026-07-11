package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.sdui.kmp.protocol.Button as ButtonNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Right-hand panel that edits the currently-selected node's primitive fields.
 *
 * Per ADR-0019 the inspector dispatches changes by handing the caller a replacement [UiNode] and
 * letting them re-thread it through [TreeMutator]. The inspector itself does not know how the
 * tree is wired together.
 *
 * This is intentionally a thin scaffold: M-S5 supports `Text.text` (string),
 * `Text.color` (token dropdown including null), and `Button.label` (string). Every other field
 * is round-tripped untouched. Adding more inspector rows is purely additive.
 */
@Composable
@Suppress("FunctionNaming")
public fun PropertyInspector(
    selected: UiNode?,
    onChange: (UiNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Inspector", style = MaterialTheme.typography.titleSmall)
            when (selected) {
                null -> Text(
                    text = "Select a node in the canvas to edit its properties.",
                    style = MaterialTheme.typography.bodySmall,
                )
                is TextNode -> TextInspector(node = selected, onChange = onChange)
                is ButtonNode -> ButtonInspector(node = selected, onChange = onChange)
                else -> Text(
                    text = "No editable fields for ${selected::class.simpleName} yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun TextInspector(node: TextNode, onChange: (UiNode) -> Unit) {
    val current = literalStringOrEmpty(node.content)
    var draft by remember(node.id) { mutableStateOf(current) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("text") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange(node.copy(content = Value.ofString(draft))) },
            enabled = draft != current,
        ) { Text("Apply text") }
    }
    ColorTokenDropdown(
        label = "color",
        selected = node.color,
        onSelect = { onChange(node.copy(color = it)) },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun ButtonInspector(node: ButtonNode, onChange: (UiNode) -> Unit) {
    val current = literalStringOrEmpty(node.label)
    var draft by remember(node.id) { mutableStateOf(current) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { onChange(node.copy(label = Value.ofString(draft))) },
        enabled = draft != current,
    ) { Text("Apply label") }
}

/**
 * Compact dropdown of all [ColorToken] entries (plus a `(none)` option for nullable slots).
 *
 * Per the protocol's third non-negotiable, raw hex / Color(...) inputs are forbidden. Operators
 * who need an unsupported colour have to pick a different token or escape to the JSON tab.
 */
@Composable
@Suppress("FunctionNaming")
private fun ColorTokenDropdown(
    label: String,
    selected: ColorToken?,
    onSelect: (ColorToken?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: ${describeToken(selected)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("(none)") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            ALL_COLOR_TOKENS.forEach { token ->
                DropdownMenuItem(
                    text = { Text(describeToken(token)) },
                    onClick = {
                        expanded = false
                        onSelect(token)
                    },
                )
            }
        }
    }
}

private fun describeToken(token: ColorToken?): String = when (token) {
    null -> "(none)"
    ColorToken.Surface -> "Surface"
    ColorToken.OnSurface -> "OnSurface"
    ColorToken.Primary -> "Primary"
    ColorToken.OnPrimary -> "OnPrimary"
    ColorToken.Error -> "Error"
    ColorToken.Warning -> "Warning"
    ColorToken.Success -> "Success"
    ColorToken.Muted -> "Muted"
    is ColorToken.Unknown -> "(unknown)"
}

private val ALL_COLOR_TOKENS: List<ColorToken> = listOf(
    ColorToken.Surface,
    ColorToken.OnSurface,
    ColorToken.Primary,
    ColorToken.OnPrimary,
    ColorToken.Error,
    ColorToken.Warning,
    ColorToken.Success,
    ColorToken.Muted,
)

/**
 * Best-effort extraction of a string literal from a [Value]. Returns the empty string for binds,
 * templates, or non-string literals — the inspector treats those as "not editable here".
 */
private fun literalStringOrEmpty(value: Value<String>): String {
    val literal = value as? Value.Literal<*> ?: return ""
    val element = literal.value
    val primitive = (element as? JsonPrimitive) ?: return ""
    return primitive.contentOrNull ?: primitive.jsonPrimitive.toString()
}
