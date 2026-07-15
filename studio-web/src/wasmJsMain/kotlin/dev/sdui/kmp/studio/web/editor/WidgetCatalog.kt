package dev.sdui.kmp.studio.web.editor

import androidx.compose.ui.graphics.vector.ImageVector
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.NavKind
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.studio.web.theme.StudioIcons
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.sdui.kmp.protocol.Text as TextNode

/** Palette grouping for a [WidgetDescriptor]. Ordering drives the palette sections. */
internal enum class WidgetCategory(val label: String) {
    Layout("Layout"),
    Content("Content"),
    Forms("Forms"),
    Media("Media"),
    Advanced("Advanced"),
}

/**
 * One spawnable widget kind in the visual editor's palette.
 *
 * Per ADR-0019 the palette fails closed: a protocol node type without a descriptor here simply
 * cannot be spawned visually (it can still arrive via the JSON tab). Every [factory] call
 * builds a fresh node with a unique id and constructor-accurate, token-only defaults.
 */
internal data class WidgetDescriptor(
    val typeName: String,
    val description: String,
    val icon: ImageVector,
    val category: WidgetCategory,
    val factory: () -> UiNode,
)

/**
 * The default catalog: every spawnable protocol node type. `UnknownUiNode` is deliberately
 * absent — it is a client-only decode sentinel, never authored.
 */
internal val DefaultWidgetPalette: List<WidgetDescriptor> = listOf(
    WidgetDescriptor(
        typeName = "Column",
        description = "Vertical stack of children",
        icon = StudioIcons.NodeColumn,
        category = WidgetCategory.Layout,
        factory = ::newColumn,
    ),
    WidgetDescriptor(
        typeName = "Text",
        description = "Static or state-bound text",
        icon = StudioIcons.NodeText,
        category = WidgetCategory.Content,
        factory = ::newText,
    ),
    WidgetDescriptor(
        typeName = "Button",
        description = "Tappable action trigger",
        icon = StudioIcons.NodeButton,
        category = WidgetCategory.Content,
        factory = ::newButton,
    ),
    WidgetDescriptor(
        typeName = "TextField",
        description = "Text input bound to state",
        icon = StudioIcons.NodeTextField,
        category = WidgetCategory.Forms,
        factory = ::newTextField,
    ),
    WidgetDescriptor(
        typeName = "Checkbox",
        description = "Boolean toggle bound to state",
        icon = StudioIcons.NodeCheckbox,
        category = WidgetCategory.Forms,
        factory = ::newCheckbox,
    ),
    WidgetDescriptor(
        typeName = "Image",
        description = "Bundled or local image",
        icon = StudioIcons.NodeImage,
        category = WidgetCategory.Media,
        factory = ::newImage,
    ),
    WidgetDescriptor(
        typeName = "AsyncImage",
        description = "Remote image with placeholder/error slots",
        icon = StudioIcons.NodeImage,
        category = WidgetCategory.Media,
        factory = ::newAsyncImage,
    ),
    WidgetDescriptor(
        typeName = "LazyList",
        description = "Virtualized list from a template",
        icon = StudioIcons.NodeList,
        category = WidgetCategory.Advanced,
        factory = ::newLazyList,
    ),
    WidgetDescriptor(
        typeName = "NavHost",
        description = "Navigation container (stack/tab/sheet)",
        icon = StudioIcons.NodeNav,
        category = WidgetCategory.Advanced,
        factory = ::newNavHost,
    ),
    WidgetDescriptor(
        typeName = "NativeSurface",
        description = "Platform escape hatch (map, player, …)",
        icon = StudioIcons.NodeNative,
        category = WidgetCategory.Advanced,
        factory = ::newNativeSurface,
    ),
)

/** Finds the descriptor for a concrete node, used by the layers panel/canvas for icons. */
internal fun descriptorFor(node: UiNode): WidgetDescriptor? =
    DefaultWidgetPalette.firstOrNull { it.typeName == node::class.simpleName }

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

/** Builds a fresh empty [Column] with a unique node id. */
internal fun newColumn(): Column = Column(
    id = freshNodeId("column"),
    since = SchemaVersion.V1,
    children = emptyList(),
)

/** Builds a fresh [TextField]; the state path derives from the node id so fields never collide. */
internal fun newTextField(): TextField {
    val id = freshNodeId("field")
    return TextField(
        id = id,
        since = SchemaVersion.V1,
        path = StatePath("form.${id.value}"),
        placeholder = Value.ofString("Enter text"),
    )
}

/** Builds a fresh [Checkbox]; the state path derives from the node id so toggles never collide. */
internal fun newCheckbox(): Checkbox {
    val id = freshNodeId("checkbox")
    return Checkbox(
        id = id,
        since = SchemaVersion.V1,
        path = StatePath("form.${id.value}"),
        label = Value.ofString("Checkbox"),
    )
}

/** Builds a fresh [Image] pointing at a placeholder asset. */
internal fun newImage(): Image = Image(
    id = freshNodeId("image"),
    since = SchemaVersion.V1,
    source = Value.ofString("asset://placeholder"),
    contentDescription = Value.ofString("Image"),
)

/** Builds a fresh [AsyncImage] with an example URL and empty slots. */
internal fun newAsyncImage(): AsyncImage = AsyncImage(
    id = freshNodeId("async-image"),
    since = SchemaVersion.V1,
    url = Value.ofString("https://example.com/image.png"),
    contentDescription = Value.ofString("Image"),
)

/**
 * Builds a fresh [LazyList] with a three-item inline source and a bound-text item template,
 * so it renders something meaningful immediately after being dropped.
 */
internal fun newLazyList(): LazyList = LazyList(
    id = freshNodeId("list"),
    since = SchemaVersion.V1,
    source = ListSource.Inline(
        items = List(LAZY_LIST_SAMPLE_COUNT) { index ->
            buildJsonObject { put("title", "Item ${index + 1}") }
        },
    ),
    itemTemplate = TextNode(
        id = freshNodeId("item"),
        since = SchemaVersion.V1,
        content = Value.Bind(StatePath("title")),
    ),
    itemKeyPath = StatePath("title"),
)

/** Builds a fresh stack [NavHost] with a single home route. */
internal fun newNavHost(): NavHost = NavHost(
    id = freshNodeId("nav"),
    since = SchemaVersion.V1,
    kind = NavKind.Stack,
    initial = Destination.ScreenDest(route = "/home"),
    routes = mapOf("home" to "/screens/home"),
)

/** Builds a fresh [NativeSurface] of the map kind with empty config. */
internal fun newNativeSurface(): NativeSurface = NativeSurface(
    id = freshNodeId("native"),
    since = SchemaVersion.V1,
    kind = "sdui.map",
    config = JsonObject(emptyMap()),
)

/**
 * Generates a node id that is unlikely to collide within a single editor session. The Studio
 * server validates uniqueness on save; collisions surface as decode-time validation errors.
 */
internal fun freshNodeId(prefix: String): NodeId =
    NodeId("$prefix-${Random.nextInt(0, Int.MAX_VALUE).toString(NODE_ID_RADIX)}")

private const val NODE_ID_RADIX: Int = 36
private const val LAZY_LIST_SAMPLE_COUNT = 3
