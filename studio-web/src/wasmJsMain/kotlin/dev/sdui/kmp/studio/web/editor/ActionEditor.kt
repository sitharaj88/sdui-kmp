package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.HttpMethod
import dev.sdui.kmp.protocol.Predicate
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.studio.web.components.studioFieldColors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Recursive structured editor over the sealed [Action] hierarchy.
 *
 * Actions are data, never code (non-negotiable #4): every subtype edits through typed pickers
 * and fields. Switching the action kind replaces the whole subtree with a sensible default
 * instance. [Action.Unknown] renders read-only — it is a client decode sentinel.
 *
 * Recursion (Sequence children, When branches, Submit callbacks) is capped at [MAX_DEPTH]
 * visual levels; deeper trees escape to the JSON tab.
 */
@Composable
@Suppress("LongMethod")
internal fun ActionEditor(
    label: String,
    action: Action,
    onCommit: (Action) -> Unit,
    depth: Int = 0,
) {
    if (depth >= MAX_DEPTH) {
        JsonEscapeChip(label)
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * NEST_INDENT_DP).dp),
    ) {
        EnumDropdown(
            label = label,
            entries = ActionKind.entries - ActionKind.Unknown,
            selected = ActionKind.of(action),
            display = { it.label },
            onSelect = { kind -> if (kind != ActionKind.of(action)) onCommit(kind.defaultInstance()) },
        )
        when (action) {
            is Action.Navigate -> {
                DestinationEditor(
                    destination = action.destination,
                    onCommit = { onCommit(action.copy(destination = it)) },
                )
                BooleanRow("replace", action.replace) { onCommit(action.copy(replace = it)) }
            }
            is Action.UpdateState -> {
                StatePathField("path", action.path) { onCommit(action.copy(path = it)) }
                UpdateValueField(action = action, onCommit = onCommit)
            }
            is Action.Sequence -> ActionListEditor(
                label = "actions",
                actions = action.actions,
                depth = depth,
                onCommit = { onCommit(action.copy(actions = it)) },
            )
            is Action.When -> {
                PredicateEditor(
                    predicate = action.condition,
                    onCommit = { onCommit(action.copy(condition = it)) },
                )
                ActionListEditor(
                    label = "then",
                    actions = action.then,
                    depth = depth,
                    onCommit = { onCommit(action.copy(then = it)) },
                )
                ActionListEditor(
                    label = "otherwise",
                    actions = action.otherwise,
                    depth = depth,
                    onCommit = { onCommit(action.copy(otherwise = it)) },
                )
            }
            is Action.Submit -> SubmitEditor(action = action, depth = depth, onCommit = onCommit)
            is Action.Unknown -> ReadOnlyRow("unknown", action.originalType)
        }
    }
}

/** The editable action kinds; Unknown is display-only and never offered as a target. */
private enum class ActionKind(val label: String) {
    Navigate("Navigate"),
    UpdateState("UpdateState"),
    Sequence("Sequence"),
    When("When"),
    Submit("Submit"),
    Unknown("Unknown"),
    ;

    fun defaultInstance(): Action = when (this) {
        Navigate -> Action.Navigate(destination = Destination.ScreenDest(route = "/"))
        UpdateState -> Action.UpdateState(path = StatePath("state.key"), value = Value.ofJson(JsonPrimitive("")))
        Sequence -> Action.Sequence(actions = emptyList())
        When -> Action.When(condition = Predicate.IsEmpty(StatePath("state.key")), then = emptyList())
        Submit -> Action.Submit(endpoint = "/api/submit")
        Unknown -> Action.Unknown()
    }

    companion object {
        fun of(action: Action): ActionKind = when (action) {
            is Action.Navigate -> Navigate
            is Action.UpdateState -> UpdateState
            is Action.Sequence -> Sequence
            is Action.When -> When
            is Action.Submit -> Submit
            is Action.Unknown -> Unknown
        }
    }
}

@Composable
private fun UpdateValueField(action: Action.UpdateState, onCommit: (Action) -> Unit) {
    val literal = action.value as? Value.Literal<*>
    if (literal == null) {
        JsonEscapeChip("value")
        return
    }
    val current = (literal.value as? JsonPrimitive)?.contentOrNull ?: literal.value.toString()
    var draft by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("value (literal)") },
            singleLine = true,
            colors = studioFieldColors(),
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft != current) {
            TextButton(
                onClick = { onCommit(action.copy(value = Value.ofJson(JsonPrimitive(draft)))) },
            ) { Text("Apply") }
        }
    }
}

/** Vertical list of nested [ActionEditor]s with remove / add-row affordances. */
@Composable
private fun ActionListEditor(
    label: String,
    actions: List<Action>,
    depth: Int,
    onCommit: (List<Action>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        InspectorSection(label)
        actions.forEachIndexed { index, child ->
            Column {
                ActionEditor(
                    label = "#${index + 1}",
                    action = child,
                    depth = depth + 1,
                    onCommit = { updated ->
                        onCommit(actions.toMutableList().apply { this[index] = updated })
                    },
                )
                Row {
                    if (index > 0) {
                        TextButton(
                            onClick = {
                                onCommit(
                                    actions.toMutableList().apply {
                                        add(index - 1, removeAt(index))
                                    },
                                )
                            },
                        ) { Text("↑") }
                    }
                    if (index < actions.lastIndex) {
                        TextButton(
                            onClick = {
                                onCommit(
                                    actions.toMutableList().apply {
                                        add(index + 1, removeAt(index))
                                    },
                                )
                            },
                        ) { Text("↓") }
                    }
                    TextButton(
                        onClick = { onCommit(actions.toMutableList().apply { removeAt(index) }) },
                    ) { Text("Remove") }
                }
            }
        }
        TextButton(
            onClick = { onCommit(actions + Action.Navigate(Destination.ScreenDest(route = "/"))) },
        ) { Text("+ Add action") }
    }
}

@Composable
private fun SubmitEditor(action: Action.Submit, depth: Int, onCommit: (Action) -> Unit) {
    EndpointField(endpoint = action.endpoint, onCommit = { onCommit(action.copy(endpoint = it)) })
    EnumDropdown(
        label = "method",
        entries = HttpMethod.entries,
        selected = action.method,
        display = { it.name },
        onSelect = { onCommit(action.copy(method = it)) },
    )
    PayloadMapEditor(payload = action.payload, onCommit = { onCommit(action.copy(payload = it)) })
    JsonEscapeChip("policy")
    ActionListEditor(
        label = "on success",
        actions = action.onSuccess,
        depth = depth,
        onCommit = { onCommit(action.copy(onSuccess = it)) },
    )
    ActionListEditor(
        label = "on error",
        actions = action.onError,
        depth = depth,
        onCommit = { onCommit(action.copy(onError = it)) },
    )
}

@Composable
private fun EndpointField(endpoint: String, onCommit: (String) -> Unit) {
    var draft by remember(endpoint) { mutableStateOf(endpoint) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("endpoint") },
            singleLine = true,
            colors = studioFieldColors(),
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        val trimmed = draft.trim()
        if (trimmed != endpoint && trimmed.isNotEmpty()) {
            TextButton(onClick = { onCommit(trimmed) }) { Text("Apply") }
        }
    }
}

/** Key → StatePath rows for [Action.Submit.payload]. */
@Composable
private fun PayloadMapEditor(payload: Map<String, StatePath>, onCommit: (Map<String, StatePath>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        InspectorSection("payload")
        payload.entries.forEach { (key, path) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(KEY_WEIGHT).padding(top = 12.dp),
                )
                Column(Modifier.weight(VALUE_WEIGHT)) {
                    StatePathField(
                        label = "path",
                        path = path,
                        onCommit = { onCommit(payload + (key to it)) },
                    )
                }
                TextButton(onClick = { onCommit(payload - key) }) { Text("✕") }
            }
        }
        var newKey by remember { mutableStateOf("") }
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text("new key") },
                singleLine = true,
                colors = studioFieldColors(),
                shape = MaterialTheme.shapes.small,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val key = newKey.trim()
                    if (key.isNotEmpty() && key !in payload) {
                        onCommit(payload + (key to StatePath("form.$key")))
                        newKey = ""
                    }
                },
            ) { Text("Add") }
        }
    }
}

/**
 * Editor for the [Predicate] condition of [Action.When]. Eq and IsEmpty edit inline; the
 * combinators (Not/All/Any) and Unknown render as JSON-tab chips — bounded v1 scope.
 */
@Composable
internal fun PredicateEditor(predicate: Predicate, onCommit: (Predicate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        when (predicate) {
            is Predicate.IsEmpty -> {
                EnumDropdown(
                    label = "condition",
                    entries = SIMPLE_PREDICATES,
                    selected = "IsEmpty",
                    display = { it },
                    onSelect = { if (it == "Eq") onCommit(Predicate.Eq(predicate.path, JsonPrimitive(""))) },
                )
                StatePathField("path", predicate.path) { onCommit(predicate.copy(path = it)) }
            }
            is Predicate.Eq -> {
                EnumDropdown(
                    label = "condition",
                    entries = SIMPLE_PREDICATES,
                    selected = "Eq",
                    display = { it },
                    onSelect = { if (it == "IsEmpty") onCommit(Predicate.IsEmpty(predicate.path)) },
                )
                StatePathField("path", predicate.path) { onCommit(predicate.copy(path = it)) }
                EqValueField(predicate = predicate, onCommit = onCommit)
            }
            else -> JsonEscapeChip("condition (${predicate::class.simpleName})")
        }
    }
}

@Composable
private fun EqValueField(predicate: Predicate.Eq, onCommit: (Predicate) -> Unit) {
    val current = (predicate.value as? JsonPrimitive)?.contentOrNull ?: predicate.value.toString()
    var draft by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("equals (literal)") },
            singleLine = true,
            colors = studioFieldColors(),
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft != current) {
            TextButton(onClick = { onCommit(predicate.copy(value = JsonPrimitive(draft))) }) { Text("Apply") }
        }
    }
}

/**
 * Editor for a [Destination]: subtype dropdown plus per-type fields. `args` objects are
 * JSON-tab territory; Unknown is read-only.
 */
@Composable
@Suppress("LongMethod")
internal fun DestinationEditor(destination: Destination, onCommit: (Destination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        EnumDropdown(
            label = "destination",
            entries = DestinationKind.entries - DestinationKind.Unknown,
            selected = DestinationKind.of(destination),
            display = { it.label },
            onSelect = { kind ->
                if (kind != DestinationKind.of(destination)) onCommit(kind.defaultInstance())
            },
        )
        when (destination) {
            is Destination.ScreenDest -> {
                RouteField(route = destination.route) { onCommit(destination.copy(route = it)) }
                if (destination.args.isNotEmpty()) JsonEscapeChip("args")
            }
            is Destination.Modal -> {
                RouteField(route = destination.route) { onCommit(destination.copy(route = it)) }
                if (destination.args.isNotEmpty()) JsonEscapeChip("args")
            }
            is Destination.Back -> CountStepper(
                count = destination.count,
                onChange = { onCommit(destination.copy(count = it)) },
            )
            is Destination.TabSwitch -> RouteField(
                label = "tab id",
                route = destination.tabId,
            ) { onCommit(destination.copy(tabId = it)) }
            Destination.PopToRoot -> Unit
            is Destination.Unknown -> ReadOnlyRow("unknown", destination.originalType)
        }
    }
}

private enum class DestinationKind(val label: String) {
    Screen("Screen"),
    Back("Back"),
    Modal("Modal"),
    Tab("Tab switch"),
    PopToRoot("Pop to root"),
    Unknown("Unknown"),
    ;

    fun defaultInstance(): Destination = when (this) {
        Screen -> Destination.ScreenDest(route = "/")
        Back -> Destination.Back()
        Modal -> Destination.Modal(route = "/")
        Tab -> Destination.TabSwitch(tabId = "home")
        PopToRoot -> Destination.PopToRoot
        Unknown -> Destination.Unknown()
    }

    companion object {
        fun of(destination: Destination): DestinationKind = when (destination) {
            is Destination.ScreenDest -> Screen
            is Destination.Back -> Back
            is Destination.Modal -> Modal
            is Destination.TabSwitch -> Tab
            Destination.PopToRoot -> PopToRoot
            is Destination.Unknown -> Unknown
        }
    }
}

@Composable
private fun RouteField(route: String, label: String = "route", onCommit: (String) -> Unit) {
    var draft by remember(route) { mutableStateOf(route) }
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
        if (trimmed != route && trimmed.isNotEmpty()) {
            TextButton(onClick = { onCommit(trimmed) }) { Text("Apply") }
        }
    }
}

@Composable
private fun CountStepper(count: Int, onChange: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ReadOnlyRow("count", count.toString())
        TextButton(onClick = { onChange((count - 1).coerceAtLeast(1)) }) { Text("−") }
        TextButton(onClick = { onChange(count + 1) }) { Text("+") }
    }
}

private val SIMPLE_PREDICATES = listOf("Eq", "IsEmpty")
private const val MAX_DEPTH = 4
private const val NEST_INDENT_DP = 8
private const val KEY_WEIGHT = 0.35f
private const val VALUE_WEIGHT = 0.65f
