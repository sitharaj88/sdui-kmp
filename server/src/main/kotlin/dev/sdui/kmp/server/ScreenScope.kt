package dev.sdui.kmp.server

import dev.sdui.kmp.protocol.A11y
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.ContentScale
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.Keyboard
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.Orientation
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.ScreenMetadata
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateDeclaration
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import kotlinx.serialization.json.JsonObject

/**
 * Builds a [Screen] top-to-bottom with a Kotlin DSL.
 *
 * The DSL assigns deterministic [dev.sdui.kmp.protocol.NodeId]s so identical blocks on two
 * server runs produce identical ids. State on the client (expanded rows, text-field entries)
 * therefore survives re-renders.
 */
public fun screen(
    id: String,
    version: SchemaVersion = SchemaVersion.V1,
    metadata: ScreenMetadata = ScreenMetadata(),
    block: ScreenScope.() -> Unit,
): Screen {
    val allocator = NodeIdAllocator(id)
    val scope = ScreenScope(allocator = allocator, parentPath = "")
    scope.block()
    val root = scope.wrapAsRoot(id)
    return Screen(
        id = ScreenId(id),
        version = version,
        root = root,
        stateDeclarations = scope.stateDeclarations,
        metadata = metadata,
    )
}

/**
 * Mutable scope collected by [screen]. Each widget factory appends to [children] and
 * allocates a stable [dev.sdui.kmp.protocol.NodeId] via [allocator].
 */
public class ScreenScope internal constructor(
    private val allocator: NodeIdAllocator,
    private val parentPath: String,
) {
    private val _children: MutableList<UiNode> = mutableListOf()
    internal val stateDeclarations: MutableList<StateDeclaration> = mutableListOf()
    internal val children: List<UiNode> get() = _children

    /**
     * Groups children in a vertical stack. When a [ScreenScope.column] is the only top-level
     * call in a screen, the screen root IS this column (no extra wrapper).
     */
    public fun column(
        spacing: Spacing = Spacing.None,
        padding: EdgeInsets = EdgeInsets.Zero,
        a11y: A11y? = null,
        block: ScreenScope.() -> Unit,
    ) {
        val nodeId = allocator.next("column", parentPath)
        val childScope = ScreenScope(allocator = allocator, parentPath = nodeId.value)
        childScope.block()
        stateDeclarations += childScope.stateDeclarations
        _children += Column(
            id = nodeId,
            children = childScope.children,
            spacing = spacing,
            padding = padding,
            a11y = a11y,
        )
    }

    public fun text(
        content: String,
        style: TextStyleToken = TextStyleToken.Body,
        color: ColorToken? = null,
        a11y: A11y? = null,
    ) {
        text(Value.ofString(content), style = style, color = color, a11y = a11y)
    }

    public fun text(
        content: Value<String>,
        style: TextStyleToken = TextStyleToken.Body,
        color: ColorToken? = null,
        a11y: A11y? = null,
    ) {
        val nodeId = allocator.next("text", parentPath)
        _children += Text(id = nodeId, content = content, style = style, color = color, a11y = a11y)
    }

    public fun button(
        label: String,
        action: Action,
        style: ButtonStyle = ButtonStyle.Primary,
        a11y: A11y? = null,
    ) {
        button(Value.ofString(label), action = action, style = style, a11y = a11y)
    }

    public fun button(
        label: Value<String>,
        action: Action,
        style: ButtonStyle = ButtonStyle.Primary,
        a11y: A11y? = null,
    ) {
        val nodeId = allocator.next("button", parentPath)
        _children += Button(id = nodeId, label = label, action = action, style = style, a11y = a11y)
    }

    public fun textField(
        path: StatePath,
        placeholder: Value<String>? = null,
        keyboard: Keyboard = Keyboard.Text,
        secure: Boolean = false,
        validation: Validation? = null,
        a11y: A11y? = null,
    ) {
        val nodeId = allocator.next("text_field", parentPath)
        _children += TextField(
            id = nodeId,
            path = path,
            placeholder = placeholder,
            keyboard = keyboard,
            secure = secure,
            validation = validation,
            a11y = a11y,
        )
    }

    public fun checkbox(
        path: StatePath,
        label: Value<String>? = null,
        a11y: A11y? = null,
    ) {
        val nodeId = allocator.next("checkbox", parentPath)
        _children += Checkbox(id = nodeId, path = path, label = label, a11y = a11y)
    }

    /**
     * Emit a virtualized list. [template] is a sub-DSL that must produce exactly one child
     * node — the item template. Each inline item's JSON keys become per-item state paths.
     */
    public fun lazyList(
        source: ListSource,
        itemKeyPath: StatePath,
        orientation: Orientation = Orientation.Vertical,
        spacing: Spacing = Spacing.None,
        padding: EdgeInsets = EdgeInsets.Zero,
        emptyState: UiNode? = null,
        loadingState: UiNode? = null,
        errorState: UiNode? = null,
        pullToRefresh: Boolean = false,
        a11y: A11y? = null,
        template: ScreenScope.() -> Unit,
    ) {
        val nodeId = allocator.next("lazy_list", parentPath)
        val templateScope = ScreenScope(allocator = allocator, parentPath = nodeId.value)
        templateScope.template()
        require(templateScope.children.size == 1) {
            "lazyList item template must produce exactly one child node, got ${templateScope.children.size}"
        }
        stateDeclarations += templateScope.stateDeclarations
        _children += LazyList(
            id = nodeId,
            source = source,
            itemTemplate = templateScope.children.single(),
            itemKeyPath = itemKeyPath,
            orientation = orientation,
            spacing = spacing,
            padding = padding,
            emptyState = emptyState,
            loadingState = loadingState,
            errorState = errorState,
            pullToRefresh = pullToRefresh,
            a11y = a11y,
        )
    }

    /**
     * Emit a platform-native surface of [kind]. Clients that don't have a factory registered
     * for the kind render the node's [fallback] (or nothing).
     */
    public fun nativeSurface(
        kind: String,
        config: JsonObject = JsonObject(emptyMap()),
        bindings: Map<String, StatePath> = emptyMap(),
        events: Map<String, List<Action>> = emptyMap(),
        fallback: UiNode? = null,
        a11y: A11y? = null,
    ) {
        val nodeId = allocator.next("native:$kind", parentPath)
        _children += NativeSurface(
            id = nodeId,
            kind = kind,
            config = config,
            bindings = bindings,
            events = events,
            fallback = fallback,
            a11y = a11y,
        )
    }

    public fun image(
        source: Value<String>,
        contentDescription: Value<String>? = null,
        contentScale: ContentScale = ContentScale.Fit,
        a11y: A11y? = null,
    ) {
        val nodeId = allocator.next("image", parentPath)
        _children += Image(
            id = nodeId,
            source = source,
            contentDescription = contentDescription,
            contentScale = contentScale,
            a11y = a11y,
        )
    }

    public fun image(
        source: String,
        contentDescription: String? = null,
        contentScale: ContentScale = ContentScale.Fit,
        a11y: A11y? = null,
    ) {
        image(
            source = Value.ofString(source),
            contentDescription = contentDescription?.let { Value.ofString(it) },
            contentScale = contentScale,
            a11y = a11y,
        )
    }

    public fun asyncImage(
        url: Value<String>,
        contentDescription: Value<String>? = null,
        contentScale: ContentScale = ContentScale.Fit,
        placeholder: UiNode? = null,
        error: UiNode? = null,
        a11y: A11y? = null,
    ) {
        val nodeId = allocator.next("async_image", parentPath)
        _children += AsyncImage(
            id = nodeId,
            url = url,
            contentDescription = contentDescription,
            contentScale = contentScale,
            placeholder = placeholder,
            error = error,
            a11y = a11y,
        )
    }

    public fun asyncImage(
        url: String,
        contentDescription: String? = null,
        contentScale: ContentScale = ContentScale.Fit,
        placeholder: UiNode? = null,
        error: UiNode? = null,
        a11y: A11y? = null,
    ) {
        asyncImage(
            url = Value.ofString(url),
            contentDescription = contentDescription?.let { Value.ofString(it) },
            contentScale = contentScale,
            placeholder = placeholder,
            error = error,
            a11y = a11y,
        )
    }

    /** Declare a state path for the linter and for initial-state setup. */
    public fun state(declaration: StateDeclaration) {
        stateDeclarations += declaration
    }

    /** Convenience binding constructor. */
    public fun binding(path: String): Value<String> = Value.Bind(StatePath(path))

    /** Convenience template-binding constructor. */
    public fun template(pattern: String, vararg bindings: Pair<String, String>): Value<String> =
        Value.template(pattern, bindings.associate { it.first to StatePath(it.second) })

    internal fun wrapAsRoot(screenId: String): UiNode {
        // When the DSL body has a single child, that child IS the root. Otherwise wrap in a
        // synthesized Column so every Screen has a single root node per the protocol spec.
        return when (_children.size) {
            1 -> _children.single()
            else -> Column(
                id = NodeId(screenId = screenId, suffix = "root"),
                children = _children.toList(),
            )
        }
    }
}

private fun NodeId(screenId: String, suffix: String): dev.sdui.kmp.protocol.NodeId =
    dev.sdui.kmp.protocol.NodeId("$screenId/$suffix")
