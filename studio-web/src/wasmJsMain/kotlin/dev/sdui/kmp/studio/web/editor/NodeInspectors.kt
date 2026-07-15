package dev.sdui.kmp.studio.web.editor

import androidx.compose.runtime.Composable
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.ContentScale
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.Keyboard
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.NavKind
import dev.sdui.kmp.protocol.Orientation
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.UnknownUiNode
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.protocol.Column as ColumnNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Per-node-type inspector sections. Each function edits one concrete type's fields via the
 * shared token-only field editors and hands a full replacement node to [onChange].
 *
 * Field coverage is exhaustive for primitives, tokens, enums, actions, and validation;
 * structured JSON payloads (NativeSurface config/bindings/events, Destination args, inline
 * list items) escape to the JSON tab by design.
 */

@Composable
internal fun ColumnInspector(node: ColumnNode, onChange: (UiNode) -> Unit) {
    EnumDropdown(
        label = "spacing",
        entries = Spacing.entries,
        selected = node.spacing,
        display = { it.name },
        onSelect = { onChange(node.copy(spacing = it)) },
    )
    EdgeInsetsEditor(
        label = "padding",
        insets = node.padding,
        onCommit = { onChange(node.copy(padding = it)) },
    )
}

@Composable
internal fun TextInspector(node: TextNode, onChange: (UiNode) -> Unit) {
    ValueStringField(
        label = "content",
        value = node.content,
        onCommit = { newValue -> newValue?.let { onChange(node.copy(content = it)) } },
    )
    EnumDropdown(
        label = "style",
        entries = TextStyleToken.entries,
        selected = node.style,
        display = { it.name },
        onSelect = { onChange(node.copy(style = it)) },
    )
    ColorTokenDropdown(
        label = "color",
        selected = node.color,
        onSelect = { onChange(node.copy(color = it)) },
    )
}

@Composable
internal fun ButtonInspector(node: Button, onChange: (UiNode) -> Unit) {
    ValueStringField(
        label = "label",
        value = node.label,
        onCommit = { newValue -> newValue?.let { onChange(node.copy(label = it)) } },
    )
    EnumDropdown(
        label = "style",
        entries = ButtonStyle.entries,
        selected = node.style,
        display = { it.name },
        onSelect = { onChange(node.copy(style = it)) },
    )
    InspectorSection("Action")
    ActionEditor(
        label = "action",
        action = node.action,
        onCommit = { onChange(node.copy(action = it)) },
    )
}

@Composable
internal fun TextFieldInspector(node: TextField, onChange: (UiNode) -> Unit) {
    StatePathField(label = "path", path = node.path) { onChange(node.copy(path = it)) }
    ValueStringField(
        label = "placeholder",
        value = node.placeholder,
        nullable = true,
        onCommit = { onChange(node.copy(placeholder = it)) },
    )
    EnumDropdown(
        label = "keyboard",
        entries = Keyboard.entries,
        selected = node.keyboard,
        display = { it.name },
        onSelect = { onChange(node.copy(keyboard = it)) },
    )
    BooleanRow("secure", node.secure) { onChange(node.copy(secure = it)) }
    ValidationEditor(validation = node.validation) { onChange(node.copy(validation = it)) }
}

@Composable
internal fun CheckboxInspector(node: Checkbox, onChange: (UiNode) -> Unit) {
    StatePathField(label = "path", path = node.path) { onChange(node.copy(path = it)) }
    ValueStringField(
        label = "label",
        value = node.label,
        nullable = true,
        onCommit = { onChange(node.copy(label = it)) },
    )
}

@Composable
internal fun ImageInspector(node: Image, onChange: (UiNode) -> Unit) {
    ValueStringField(
        label = "source",
        value = node.source,
        onCommit = { newValue -> newValue?.let { onChange(node.copy(source = it)) } },
    )
    ValueStringField(
        label = "alt text",
        value = node.contentDescription,
        nullable = true,
        onCommit = { onChange(node.copy(contentDescription = it)) },
    )
    EnumDropdown(
        label = "scale",
        entries = ContentScale.entries,
        selected = node.contentScale,
        display = { it.name },
        onSelect = { onChange(node.copy(contentScale = it)) },
    )
}

@Composable
internal fun AsyncImageInspector(node: AsyncImage, onChange: (UiNode) -> Unit) {
    ValueStringField(
        label = "url",
        value = node.url,
        onCommit = { newValue -> newValue?.let { onChange(node.copy(url = it)) } },
    )
    ValueStringField(
        label = "alt text",
        value = node.contentDescription,
        nullable = true,
        onCommit = { onChange(node.copy(contentDescription = it)) },
    )
    EnumDropdown(
        label = "scale",
        entries = ContentScale.entries,
        selected = node.contentScale,
        display = { it.name },
        onSelect = { onChange(node.copy(contentScale = it)) },
    )
    InspectorSection("Slots")
    SlotRow(
        label = "placeholder",
        slot = node.placeholder,
        onSet = { onChange(node.copy(placeholder = newText())) },
        onClear = { onChange(node.copy(placeholder = null)) },
    )
    SlotRow(
        label = "error",
        slot = node.error,
        onSet = { onChange(node.copy(error = newText())) },
        onClear = { onChange(node.copy(error = null)) },
    )
}

@Composable
@Suppress("LongMethod")
internal fun LazyListInspector(node: LazyList, onChange: (UiNode) -> Unit) {
    ListSourceEditor(source = node.source, onCommit = { onChange(node.copy(source = it)) })
    StatePathField(label = "item key", path = node.itemKeyPath) { onChange(node.copy(itemKeyPath = it)) }
    EnumDropdown(
        label = "orientation",
        entries = Orientation.entries,
        selected = node.orientation,
        display = { it.name },
        onSelect = { onChange(node.copy(orientation = it)) },
    )
    EnumDropdown(
        label = "spacing",
        entries = Spacing.entries,
        selected = node.spacing,
        display = { it.name },
        onSelect = { onChange(node.copy(spacing = it)) },
    )
    EdgeInsetsEditor(
        label = "padding",
        insets = node.padding,
        onCommit = { onChange(node.copy(padding = it)) },
    )
    BooleanRow("pull to refresh", node.pullToRefresh) { onChange(node.copy(pullToRefresh = it)) }
    InspectorSection("Slots")
    SlotRow(
        label = "template",
        slot = node.itemTemplate,
        onSet = {},
        // itemTemplate is required by the protocol — offer replace-with-fresh instead of clear.
        onClear = { onChange(node.copy(itemTemplate = newText())) },
    )
    SlotRow(
        label = "empty",
        slot = node.emptyState,
        onSet = { onChange(node.copy(emptyState = newText())) },
        onClear = { onChange(node.copy(emptyState = null)) },
    )
    SlotRow(
        label = "loading",
        slot = node.loadingState,
        onSet = { onChange(node.copy(loadingState = newText())) },
        onClear = { onChange(node.copy(loadingState = null)) },
    )
    SlotRow(
        label = "error",
        slot = node.errorState,
        onSet = { onChange(node.copy(errorState = newText())) },
        onClear = { onChange(node.copy(errorState = null)) },
    )
}

@Composable
private fun ListSourceEditor(source: ListSource, onCommit: (ListSource) -> Unit) {
    EnumDropdown(
        label = "source",
        entries = LIST_SOURCE_KINDS,
        selected = sourceKind(source),
        display = { it },
        onSelect = { kind ->
            if (kind != sourceKind(source)) {
                onCommit(
                    when (kind) {
                        "Inline" -> ListSource.Inline(items = emptyList())
                        "Paged" -> ListSource.Paged(endpoint = "/api/items")
                        else -> ListSource.Bound(path = dev.sdui.kmp.protocol.StatePath("items"))
                    },
                )
            }
        },
    )
    when (source) {
        is ListSource.Inline -> {
            ReadOnlyRow("items", "${source.items.size} inline item(s)")
            JsonEscapeChip("item data")
        }
        is ListSource.Paged -> {
            ReadOnlyRow("endpoint", source.endpoint)
            ReadOnlyRow("page size", source.pageSize.toString())
            JsonEscapeChip("paging details")
        }
        is ListSource.Bound -> StatePathField(label = "bound path", path = source.path) {
            onCommit(source.copy(path = it))
        }
        is ListSource.Unknown -> ReadOnlyRow("unknown", source.originalType)
    }
}

private fun sourceKind(source: ListSource): String = when (source) {
    is ListSource.Inline -> "Inline"
    is ListSource.Paged -> "Paged"
    is ListSource.Bound -> "Bound"
    is ListSource.Unknown -> "Unknown"
}

@Composable
internal fun NavHostInspector(node: NavHost, onChange: (UiNode) -> Unit) {
    EnumDropdown(
        label = "kind",
        entries = NavKind.entries,
        selected = node.kind,
        display = { it.name },
        onSelect = { onChange(node.copy(kind = it)) },
    )
    InspectorSection("Initial destination")
    DestinationEditor(
        destination = node.initial,
        onCommit = { onChange(node.copy(initial = it)) },
    )
    InspectorSection("Routes")
    node.routes.forEach { (name, target) ->
        ReadOnlyRow(name, target)
    }
    JsonEscapeChip("route map")
}

@Composable
internal fun NativeSurfaceInspector(node: NativeSurface, onChange: (UiNode) -> Unit) {
    ValueStringField(
        label = "kind",
        value = Value.ofString(node.kind),
        onCommit = { newValue ->
            val literal = newValue as? Value.Literal<*> ?: return@ValueStringField
            val kind = literalString(literal).trim()
            if (kind.isNotEmpty()) onChange(node.copy(kind = kind))
        },
    )
    JsonEscapeChip("config")
    JsonEscapeChip("bindings")
    JsonEscapeChip("events")
}

@Composable
internal fun UnknownInspector(node: UnknownUiNode) {
    ReadOnlyRow("original type", node.originalType)
    JsonEscapeChip("payload")
}

private val LIST_SOURCE_KINDS = listOf("Inline", "Paged", "Bound")
