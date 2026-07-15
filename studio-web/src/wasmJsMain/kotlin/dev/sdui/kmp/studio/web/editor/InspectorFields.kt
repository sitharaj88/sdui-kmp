package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.studio.web.components.studioFieldColors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reusable, token-only field editors for the property inspector.
 *
 * Per ADR-0019 and the framework's third non-negotiable there is deliberately NO hex picker,
 * numeric dp input, or font-name field anywhere in this file. Colors are a hard-coded token
 * list; spacing/typography/enums come from `entries`. Exotic values (`Value.Bind`,
 * `Value.Template`, non-primitive JSON) render as read-only chips pointing at the JSON tab.
 */

/** Section header inside the inspector. */
@Composable
internal fun InspectorSection(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Read-only labeled value row (node id, since, …). */
@Composable
internal fun ReadOnlyRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        InspectorLabel(label)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chip that marks a field as editable only through the JSON tab. */
@Composable
internal fun JsonEscapeChip(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        InspectorLabel(label)
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = "edit in JSON tab",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Editor for a `Value<String>` field.
 *
 * Literals edit inline and commit on every change **debounced by the caller's tree-equality
 * guard** — the text field keeps its own draft and commits on focus-style "Apply" via the
 * trailing check, or immediately when [commitOnChange] is set. Binds and templates render as
 * read-only chips; [nullable] fields add a clear/set toggle.
 */
@Composable
@Suppress("LongMethod")
internal fun ValueStringField(
    label: String,
    value: Value<String>?,
    onCommit: (Value<String>?) -> Unit,
    modifier: Modifier = Modifier,
    nullable: Boolean = false,
) {
    when (value) {
        null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
            InspectorLabel(label)
            Text(
                text = "(not set)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (nullable) {
                TextButton(onClick = { onCommit(Value.ofString("")) }) { Text("Set") }
            }
        }
        is Value.Literal<*> -> {
            val current = literalString(value)
            var draft by remember(current) { mutableStateOf(current) }
            Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(label) },
                    singleLine = true,
                    colors = studioFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft != current || nullable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (draft != current) {
                            TextButton(onClick = { onCommit(Value.ofString(draft)) }) { Text("Apply") }
                        }
                        if (nullable) {
                            TextButton(onClick = { onCommit(null) }) { Text("Clear") }
                        }
                    }
                }
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
            InspectorLabel(label)
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = dynamicValueSummary(value),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Generic dropdown over an enum's `entries`. */
@Composable
internal fun <T> EnumDropdown(
    label: String,
    entries: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        InspectorLabel(label)
        Box {
            OutlinedButton(onClick = { expanded = true }, shape = MaterialTheme.shapes.small) {
                Text(display(selected), style = MaterialTheme.typography.labelLarge)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(display(entry)) },
                        onClick = {
                            expanded = false
                            onSelect(entry)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Dropdown over the hard-coded [ColorToken] list (plus `(none)` when nullable), with a color
 * swatch per entry resolved through the canvas token map. Still token-only — the swatch is a
 * preview, never an input.
 */
@Composable
internal fun ColorTokenDropdown(
    label: String,
    selected: ColorToken?,
    onSelect: (ColorToken?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        InspectorLabel(label)
        Box {
            OutlinedButton(onClick = { expanded = true }, shape = MaterialTheme.shapes.small) {
                Box(
                    Modifier
                        .size(SWATCH_SIZE)
                        .background(selected.toCanvasColor(), CircleShape),
                )
                Text(
                    text = "  ${describeToken(selected)}",
                    style = MaterialTheme.typography.labelLarge,
                )
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
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(SWATCH_SIZE)
                                    .background(token.toCanvasColor(), CircleShape),
                            )
                        },
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
}

/** Per-edge [EdgeInsets] editor built from four [Spacing] dropdowns plus an "all" shortcut. */
@Composable
internal fun EdgeInsetsEditor(
    label: String,
    insets: EdgeInsets,
    onCommit: (EdgeInsets) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            InspectorLabel(label)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "per-edge ▾" else "per-edge ▸", style = MaterialTheme.typography.labelSmall)
            }
        }
        EnumDropdown(
            label = "all",
            entries = Spacing.entries,
            selected = insets.top,
            display = { it.name },
            onSelect = { onCommit(EdgeInsets(top = it, start = it, end = it, bottom = it)) },
        )
        if (expanded) {
            EnumDropdown("top", Spacing.entries, insets.top, { it.name }) { onCommit(insets.copy(top = it)) }
            EnumDropdown("start", Spacing.entries, insets.start, { it.name }) { onCommit(insets.copy(start = it)) }
            EnumDropdown("end", Spacing.entries, insets.end, { it.name }) { onCommit(insets.copy(end = it)) }
            EnumDropdown("bottom", Spacing.entries, insets.bottom, { it.name }) { onCommit(insets.copy(bottom = it)) }
        }
    }
}

/** Labeled switch row. */
@Composable
internal fun BooleanRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        InspectorLabel(label)
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/** State-path text field; commits trimmed non-empty paths only. */
@Composable
internal fun StatePathField(label: String, path: StatePath, onCommit: (StatePath) -> Unit) {
    var draft by remember(path.value) { mutableStateOf(path.value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(label) },
            singleLine = true,
            colors = studioFieldColors(),
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        val trimmed = draft.trim()
        if (trimmed != path.value && trimmed.isNotEmpty()) {
            TextButton(onClick = { onCommit(StatePath(trimmed)) }) { Text("Apply") }
        }
    }
}

/** Summary row for a named single-slot child with set/clear affordances. */
@Composable
internal fun SlotRow(
    label: String,
    slot: UiNode?,
    onSet: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        InspectorLabel(label)
        Text(
            text = slot?.let { it::class.simpleName ?: "node" } ?: "(empty)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (slot == null) {
            TextButton(onClick = onSet) { Text("Set") }
        } else if (onClear != null) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/**
 * Editor for [Validation]: a flat checklist of Required / Email plus MinLength / MaxLength
 * with steppers. One level of [Validation.All] is normalized; anything more exotic (nested
 * All, Unknown, custom messages) renders as a JSON-tab chip. Commits `null` (no rules), the
 * single rule, or `All(rules)`.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun ValidationEditor(validation: Validation?, onCommit: (Validation?) -> Unit) {
    val rules = when (validation) {
        null -> emptyList()
        is Validation.All -> validation.validations
        else -> listOf(validation)
    }
    val exotic = rules.any {
        it is Validation.All || it is Validation.Unknown ||
            (it is Validation.Required && it.message != null) ||
            (it is Validation.Email && it.message != null) ||
            (it is Validation.MinLength && it.message != null) ||
            (it is Validation.MaxLength && it.message != null)
    }
    if (exotic) {
        JsonEscapeChip("validation")
        return
    }
    fun commit(newRules: List<Validation>) {
        onCommit(
            when {
                newRules.isEmpty() -> null
                newRules.size == 1 -> newRules.single()
                else -> Validation.All(newRules)
            },
        )
    }

    val required = rules.filterIsInstance<Validation.Required>().firstOrNull()
    val email = rules.filterIsInstance<Validation.Email>().firstOrNull()
    val minLength = rules.filterIsInstance<Validation.MinLength>().firstOrNull()
    val maxLength = rules.filterIsInstance<Validation.MaxLength>().firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InspectorSection("Validation")
        BooleanRow("required", required != null) { on ->
            commit(if (on) rules + Validation.Required() else rules - listOfNotNull(required).toSet())
        }
        BooleanRow("email", email != null) { on ->
            commit(if (on) rules + Validation.Email() else rules - listOfNotNull(email).toSet())
        }
        LengthRuleRow(
            label = "min length",
            rule = minLength?.length,
            onChange = { newLength ->
                val without = rules - listOfNotNull(minLength).toSet()
                commit(if (newLength == null) without else without + Validation.MinLength(newLength))
            },
        )
        LengthRuleRow(
            label = "max length",
            rule = maxLength?.length,
            onChange = { newLength ->
                val without = rules - listOfNotNull(maxLength).toSet()
                commit(if (newLength == null) without else without + Validation.MaxLength(newLength))
            },
        )
    }
}

@Composable
private fun LengthRuleRow(label: String, rule: Int?, onChange: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        InspectorLabel(label)
        if (rule == null) {
            TextButton(onClick = { onChange(DEFAULT_LENGTH_RULE) }) { Text("Add") }
        } else {
            TextButton(onClick = { onChange((rule - 1).coerceAtLeast(0)) }) { Text("−") }
            Text(
                text = rule.toString(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            TextButton(onClick = { onChange(rule + 1) }) { Text("+") }
            TextButton(onClick = { onChange(null) }) { Text("Remove") }
        }
    }
}

@Composable
private fun InspectorLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(LABEL_WIDTH),
    )
}

/** Display name for a [ColorToken] (or `(none)`). */
internal fun describeToken(token: ColorToken?): String = when (token) {
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

/**
 * The hard-coded token list, per ADR-0019 — enumerated at the call site so a reviewer can see
 * the inspector never grows a hex input.
 */
internal val ALL_COLOR_TOKENS: List<ColorToken> = listOf(
    ColorToken.Surface,
    ColorToken.OnSurface,
    ColorToken.Primary,
    ColorToken.OnPrimary,
    ColorToken.Error,
    ColorToken.Warning,
    ColorToken.Success,
    ColorToken.Muted,
)

/** The string content of a literal [Value], or "" for non-string payloads. */
internal fun literalString(value: Value.Literal<*>): String =
    (value.value as? JsonPrimitive)?.contentOrNull ?: ""

/** Short monospace summary for bind/template/unknown values. */
internal fun dynamicValueSummary(value: Value<*>): String = when (value) {
    is Value.Bind<*> -> "bind: ${value.path.value}"
    is Value.Template -> "template: \"${value.pattern}\""
    is Value.Unknown -> "unknown: ${value.originalType}"
    is Value.Literal<*> -> literalString(value)
}

private val LABEL_WIDTH = 84.dp
private val SWATCH_SIZE = 12.dp
private const val DEFAULT_LENGTH_RULE = 3
