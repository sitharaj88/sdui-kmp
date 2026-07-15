package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.Orientation
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.studio.web.theme.StudioIcons
import dev.sdui.kmp.protocol.Column as ColumnNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Renders one node of the tree as an inert, WYSIWYG-faithful facsimile wrapped in
 * [NodeChrome].
 *
 * Per ADR-0019 this is a hand-written renderer, NOT `SduiHost`: nothing here is clickable,
 * focusable, or state-bound, so the editor can never fire an action or mutate a store.
 * Interactive-looking widgets (Button, TextField, Checkbox) are built from plain
 * `Surface`/`Box` primitives so the selection layer owns every pointer event.
 *
 * [interactive] is false inside slot subtrees (AsyncImage.placeholder, LazyList.itemTemplate,
 * …): those render visuals only — no selection, no geometry registration — because [TreePath]
 * cannot address named slots. Slot contents are edited via the inspector or the JSON tab.
 */
@Composable
internal fun CanvasNode(
    node: UiNode,
    path: TreePath,
    workspace: EditorWorkspaceState,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    NodeChrome(
        node = node,
        path = path,
        workspace = workspace,
        interactive = interactive,
        modifier = modifier,
    ) {
        when (node) {
            is ColumnNode -> ColumnFacsimile(node, path, workspace, interactive)
            is TextNode -> TextFacsimile(node)
            is Button -> ButtonFacsimile(node)
            is dev.sdui.kmp.protocol.TextField -> TextFieldFacsimile(node)
            is Checkbox -> CheckboxFacsimile(node)
            is Image -> ImageFacsimile(label = "Image", source = node.source, scale = node.contentScale.name)
            is AsyncImage -> AsyncImageFacsimile(node, workspace)
            is LazyList -> LazyListFacsimile(node, workspace)
            is NavHost -> NavHostFacsimile(node)
            is NativeSurface -> NativeSurfaceFacsimile(node)
            else -> UnknownFacsimile(node)
        }
    }
}

// -- Column ---------------------------------------------------------------------------------

@Composable
private fun ColumnFacsimile(
    node: ColumnNode,
    path: TreePath,
    workspace: EditorWorkspaceState,
    interactive: Boolean,
) {
    if (node.children.isEmpty()) {
        EmptyContainerHint()
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(node.spacing.toCanvasDp()),
        modifier = Modifier.fillMaxWidth().padding(node.padding.toCanvasPadding()),
    ) {
        node.children.forEachIndexed { index, child ->
            CanvasNode(
                node = child,
                path = path + index,
                workspace = workspace,
                interactive = interactive,
            )
        }
    }
}

/** Dashed "Drop widgets here" hint filling an empty container. */
@Composable
private fun EmptyContainerHint() {
    val hint = LocalChromeColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = EMPTY_CONTAINER_MIN_HEIGHT)
            .dashedBorder(hint.placeholderText),
    ) {
        Text(
            text = "Drop widgets here",
            style = MaterialTheme.typography.bodySmall,
            color = hint.placeholderText,
        )
    }
}

// -- Text -----------------------------------------------------------------------------------

@Composable
private fun TextFacsimile(node: TextNode) {
    val preview = previewText(node.content)
    val baseStyle = node.style.toCanvasStyle()
    if (preview.isPlaceholder()) {
        Text(
            text = preview.displayString().ifEmpty { "(no content)" },
            style = baseStyle.copy(fontStyle = FontStyle.Italic),
            color = LocalChromeColors.current.placeholderText,
        )
    } else {
        Text(
            text = preview.displayString(),
            style = baseStyle,
            color = node.color.toCanvasColor(),
        )
    }
}

// -- Button ---------------------------------------------------------------------------------

@Composable
private fun ButtonFacsimile(node: Button) {
    val label = previewText(node.label)
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (node.style) {
        ButtonStyle.Primary -> colors.primary to colors.onPrimary
        ButtonStyle.Secondary -> colors.secondaryContainer to colors.onSecondaryContainer
        ButtonStyle.Tertiary -> Color.Transparent to colors.primary
        ButtonStyle.Destructive -> colors.error to colors.onError
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Text(
            text = label.displayString().ifEmpty { "Button" },
            style = MaterialTheme.typography.labelLarge,
            color = content,
            modifier = Modifier.padding(horizontal = BUTTON_H_PADDING, vertical = BUTTON_V_PADDING),
        )
    }
}

// -- Forms ----------------------------------------------------------------------------------

@Composable
private fun TextFieldFacsimile(node: dev.sdui.kmp.protocol.TextField) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .outlineBorder(colors.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            val placeholder = if (node.secure) {
                "•••••••"
            } else {
                previewText(node.placeholder).displayString().ifEmpty { "Text input" }
            }
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (node.secure) {
                Icon(
                    imageVector = StudioIcons.Lock,
                    contentDescription = "secure field",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(FIELD_BADGE_ICON_SIZE),
                )
            }
            Text(
                text = node.keyboard.name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun CheckboxFacsimile(node: Checkbox) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(CHECKBOX_SIZE)
                .outlineBorder(colors.outline),
        )
        val label = previewText(node.label)
        Text(
            text = label.displayString().ifEmpty { "Checkbox" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (label.isPlaceholder()) colors.onSurfaceVariant else colors.onSurface,
        )
    }
}

// -- Media ----------------------------------------------------------------------------------

@Composable
private fun ImageFacsimile(label: String, source: Value<String>, scale: String, badge: String? = null) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(IMAGE_FRAME_HEIGHT)
                .background(colors.surfaceVariant, MaterialTheme.shapes.small),
        ) {
            Icon(
                imageVector = StudioIcons.NodeImage,
                contentDescription = label,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(IMAGE_ICON_SIZE),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            if (badge != null) {
                CanvasBadge(badge)
            }
            CanvasBadge(scale)
            Text(
                text = previewText(source).displayString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AsyncImageFacsimile(node: AsyncImage, workspace: EditorWorkspaceState) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ImageFacsimile(
            label = "AsyncImage",
            source = node.url,
            scale = node.contentScale.name,
            badge = "async",
        )
        node.placeholder?.let { slot ->
            SlotFrame(label = "placeholder") {
                CanvasNode(node = slot, path = emptyList(), workspace = workspace, interactive = false)
            }
        }
        node.error?.let { slot ->
            SlotFrame(label = "error") {
                CanvasNode(node = slot, path = emptyList(), workspace = workspace, interactive = false)
            }
        }
    }
}

// -- LazyList -------------------------------------------------------------------------------

@Composable
private fun LazyListFacsimile(node: LazyList, workspace: EditorWorkspaceState) {
    val sourceBadge = when (val source = node.source) {
        is ListSource.Inline -> "inline (${source.items.size} items)"
        is ListSource.Paged -> "paged: ${source.endpoint}"
        is ListSource.Bound -> "bound: {${source.path.value}}"
        is ListSource.Unknown -> "unknown source"
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CanvasBadge(sourceBadge)
        SlotFrame(label = "item template × $TEMPLATE_REPEAT") {
            val spacing = node.spacing.toCanvasDp()
            val content: @Composable () -> Unit = {
                repeat(TEMPLATE_REPEAT) {
                    CanvasNode(
                        node = node.itemTemplate,
                        path = emptyList(),
                        workspace = workspace,
                        interactive = false,
                    )
                }
            }
            if (node.orientation == Orientation.Horizontal) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.padding(node.padding.toCanvasPadding()),
                ) { content() }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.padding(node.padding.toCanvasPadding()),
                ) { content() }
            }
        }
        node.emptyState?.let { slot ->
            SlotFrame(label = "empty state") {
                CanvasNode(node = slot, path = emptyList(), workspace = workspace, interactive = false)
            }
        }
        node.loadingState?.let { slot ->
            SlotFrame(label = "loading state") {
                CanvasNode(node = slot, path = emptyList(), workspace = workspace, interactive = false)
            }
        }
        node.errorState?.let { slot ->
            SlotFrame(label = "error state") {
                CanvasNode(node = slot, path = emptyList(), workspace = workspace, interactive = false)
            }
        }
    }
}

// -- Nav / native / unknown -----------------------------------------------------------------

@Composable
private fun NavHostFacsimile(node: NavHost) {
    FramedPlaceholder(
        icon = { tint ->
            Icon(
                imageVector = StudioIcons.NodeNav,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(PLACEHOLDER_ICON_SIZE),
            )
        },
        title = "NavHost · ${node.kind.name}",
        subtitle = "${node.routes.size} route(s)",
    )
}

@Composable
private fun NativeSurfaceFacsimile(node: NativeSurface) {
    FramedPlaceholder(
        icon = { tint ->
            Icon(
                imageVector = StudioIcons.NodeNative,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(PLACEHOLDER_ICON_SIZE),
            )
        },
        title = node.kind,
        subtitle = "native surface",
    )
}

@Composable
private fun UnknownFacsimile(node: UiNode) {
    FramedPlaceholder(
        icon = { tint ->
            Icon(
                imageVector = StudioIcons.NodeUnknown,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(PLACEHOLDER_ICON_SIZE),
            )
        },
        title = node::class.simpleName ?: "Unknown node",
        subtitle = "no visual renderer",
    )
}

@Composable
private fun FramedPlaceholder(
    icon: @Composable (Color) -> Unit,
    title: String,
    subtitle: String,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        icon(colors.onSurfaceVariant)
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

// -- Shared pieces --------------------------------------------------------------------------

/**
 * Dashed frame with a corner label used for named slot children (`placeholder`,
 * `item template`, …). Slot contents render non-interactive — see [CanvasNode].
 */
@Composable
internal fun SlotFrame(label: String, content: @Composable () -> Unit) {
    val hint = LocalChromeColors.current.placeholderText
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dashedBorder(hint)
            .padding(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = hint,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

/** Tiny muted info badge (source kind, content scale, …). */
@Composable
private fun CanvasBadge(text: String) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraSmall, color = colors.surfaceVariant) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 1px dashed rounded border. */
private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = DASH_STROKE_PX,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON_PX, DASH_OFF_PX)),
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(DASH_CORNER_PX, DASH_CORNER_PX),
    )
}

/** 1px solid rounded outline, the form-widget resting border. */
private fun Modifier.outlineBorder(color: Color): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(width = DASH_STROKE_PX),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(DASH_CORNER_PX, DASH_CORNER_PX),
    )
}

private val EMPTY_CONTAINER_MIN_HEIGHT = 56.dp
private val BUTTON_H_PADDING = 16.dp
private val BUTTON_V_PADDING = 8.dp
private val FIELD_HEIGHT = 44.dp
private val FIELD_BADGE_ICON_SIZE = 12.dp
private val CHECKBOX_SIZE = 18.dp
private val IMAGE_FRAME_HEIGHT = 120.dp
private val IMAGE_ICON_SIZE = 28.dp
private val PLACEHOLDER_ICON_SIZE = 20.dp
private const val TEMPLATE_REPEAT = 3
private const val DASH_STROKE_PX = 1f
private const val DASH_ON_PX = 6f
private const val DASH_OFF_PX = 4f
private const val DASH_CORNER_PX = 8f
