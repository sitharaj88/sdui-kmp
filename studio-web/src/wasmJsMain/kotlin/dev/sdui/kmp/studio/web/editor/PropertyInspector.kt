package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.UnknownUiNode
import dev.sdui.kmp.studio.web.theme.StudioIcons
import dev.sdui.kmp.protocol.Button as ButtonNode
import dev.sdui.kmp.protocol.Column as ColumnNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Right-hand panel that edits the currently-selected node's fields.
 *
 * Per ADR-0019 the inspector dispatches changes by handing the caller a replacement [UiNode]
 * and letting them re-thread it through the workspace's commit; the inspector itself does not
 * know how the tree is wired together. The hand-written per-type `when` (over reflection) is
 * deliberate — token enforcement must be visible at the call site.
 *
 * Coverage: every concrete node type gets a section (see `NodeInspectors.kt`); a common
 * header shows the type, id, and schema version. Structured JSON fields and `Value.Bind` /
 * `Value.Template` show as read-only "edit in JSON tab" chips. Accessibility (`a11y`) editing
 * is deferred — also a JSON-tab chip for now.
 */
@Composable
@Suppress("FunctionNaming", "CyclomaticComplexMethod")
public fun PropertyInspector(
    selected: UiNode?,
    onChange: (UiNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected == null) {
            InspectorEmptyState()
            return@Column
        }

        CommonHeader(selected)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (selected) {
            is ColumnNode -> ColumnInspector(selected, onChange)
            is TextNode -> TextInspector(selected, onChange)
            is ButtonNode -> ButtonInspector(selected, onChange)
            is TextField -> TextFieldInspector(selected, onChange)
            is Checkbox -> CheckboxInspector(selected, onChange)
            is Image -> ImageInspector(selected, onChange)
            is AsyncImage -> AsyncImageInspector(selected, onChange)
            is LazyList -> LazyListInspector(selected, onChange)
            is NavHost -> NavHostInspector(selected, onChange)
            is NativeSurface -> NativeSurfaceInspector(selected, onChange)
            is UnknownUiNode -> UnknownInspector(selected)
            else -> Text(
                text = "No editable fields for ${selected::class.simpleName} yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        JsonEscapeChip("a11y")
    }
}

@Composable
private fun CommonHeader(node: UiNode) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = descriptorFor(node)?.icon ?: StudioIcons.NodeUnknown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(HEADER_ICON_SIZE),
        )
        Text(
            text = node::class.simpleName ?: "Node",
            style = MaterialTheme.typography.titleSmall,
        )
    }
    ReadOnlyRow("id", node.id.value)
    ReadOnlyRow("since", "v${node.since.value}")
}

/**
 * Friendly inspector empty state shown when nothing is selected: a muted glyph, a short title,
 * and a hint. Callers with the tree in hand (the visual editor) pass an [overview] slot to trail
 * a read-only "Screen overview" card; the plain [PropertyInspector] null case omits it.
 */
@Composable
internal fun InspectorEmptyState(
    modifier: Modifier = Modifier,
    overview: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = EMPTY_TOP_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EMPTY_GAP),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(EMPTY_ICON_BOX),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = StudioIcons.Eye,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = EMPTY_ICON_ALPHA),
                    modifier = Modifier.size(EMPTY_ICON_SIZE),
                )
            }
        }
        Text(
            text = "No selection",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Select a node on the canvas or in Layers to edit its properties.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = EMPTY_HINT_H_PADDING),
        )
        if (overview != null) {
            Spacer(Modifier.height(EMPTY_OVERVIEW_GAP))
            overview()
        }
    }
}

/**
 * Compact, read-only "Screen overview" card for the inspector's empty state: the screen id and
 * a total node count walked from the tree. Token-only and non-editable per ADR-0019 — there are
 * no screen-level fields to change here.
 */
@Composable
internal fun ScreenOverviewCard(screenId: String, nodeCount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(OVERVIEW_PADDING),
            verticalArrangement = Arrangement.spacedBy(OVERVIEW_GAP),
        ) {
            Text(
                text = "SCREEN OVERVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OverviewRow(label = "id", value = screenId)
            OverviewRow(label = "nodes", value = nodeCount.toString())
        }
    }
}

@Composable
private fun OverviewRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(OVERVIEW_LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Total number of path-addressable nodes in the subtree (this node plus container descendants). */
internal fun UiNode.nodeCount(): Int = 1 + when (this) {
    is dev.sdui.kmp.protocol.Container -> children.sumOf { it.nodeCount() }
    else -> 0
}

private val HEADER_ICON_SIZE = 16.dp
private val EMPTY_TOP_PADDING = 24.dp
private val EMPTY_GAP = 8.dp
private val EMPTY_ICON_BOX = 56.dp
private val EMPTY_ICON_SIZE = 26.dp
private const val EMPTY_ICON_ALPHA = 0.7f
private val EMPTY_HINT_H_PADDING = 12.dp
private val EMPTY_OVERVIEW_GAP = 8.dp
private val OVERVIEW_PADDING = 12.dp
private val OVERVIEW_GAP = 6.dp
private val OVERVIEW_LABEL_WIDTH = 56.dp
